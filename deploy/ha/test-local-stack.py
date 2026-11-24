#!/usr/bin/env python3
"""Rancher-only full-stack failure drill. Uses disposable data and at most four synthetic webhook calls."""
import datetime,http.client,base64,hashlib,importlib.util,json,os,pathlib,shutil,ssl,subprocess,time,urllib.error,urllib.request,uuid
ROOT=pathlib.Path(__file__).resolve().parent

def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/file);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def out(args):return subprocess.check_output(args,text=True).strip()
def wait(check,message,seconds=180):
    for _ in range(seconds):
        try:
            value=check()
            if value:return value
        except (OSError,ValueError,subprocess.CalledProcessError):pass
        time.sleep(1)
    raise RuntimeError(message)

def validate(context):
    work=context['work'];projects=context['projects'];inventory=context['inventory'];secret=context['secrets'];host=context['host_path'];client=context['client']
    nodes=inventory['nodes'];addresses=','.join(n['private_ip'] for n in nodes);primary_dsn='host='+addresses+' port=5432 user=notification_owner password='+secret['pg_owner_password']+' dbname=notifications sslmode=verify-full sslrootcert=/tls/postgres-ca.crt target_session_attrs=read-write connect_timeout=3'
    rate=load('local_rate','redis-cluster.py');rate_secrets=rate.credentials()
    inventory['redis']={'enabled':True};secret.update(redis_limiter=rate_secrets['limiter'],redis_discovery=rate_secrets['discovery'])
    rate.create_pki(inventory,work/'redis-pki')
    redis_image=os.environ.get('REDIS_IMAGE','public.ecr.aws/docker/library/redis:8.10.3@sha256:04d1eefe18169c3349884dc2a552f6c429d9a9d8eda4caab13d2b826dc6dfc4c')
    report={'tested_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'three simulated nodes on one Rancher VM; independent host fencing requires cloud verification','checks':{},'acknowledged_event_ids':[]}
    checks=report['checks'];vip='172.29.0.100';frontend='notification-ha-ci-frontend'
    pk=load('local_pki','create-pki.py');pk.issue(work/'pki/offline-ca/postgres',work/'ingress','server','local-ha-ingress',vip)
    context['sql']('SELECT 1')
    run(['docker','cp',str(work/'secrets.json'),client+':/config/secrets.json'])
    run(['docker','exec','-e','PG_ADMIN_DSN='+primary_dsn.replace('dbname=notifications','dbname=postgres'),client,'python3','/opt/ha/initialize-databases.py'])
    for node,command in zip(nodes,projects):
        directory=work/('node'+str(node['id']));config=json.loads((directory/'compose.yaml').read_text());full,_=context['render'](inventory,secret,node['id'])
        rate_root=directory/'rate';rate.bootstrap(inventory,rate_secrets,node['id'],redis_image,rate_root)
        rate_config,_=rate.render(inventory,rate_secrets,node['id'],redis_image)
        shutil.copytree(work/'redis-pki'/('node'+str(node['id'])),rate_root/'tls')
        os.chown(rate_root/'tls',999,999)
        for cert in (rate_root/'tls').iterdir():os.chown(cert,999,999)
        (directory/'tls/app/redis-ca.crt').write_bytes((work/'redis-pki/offline-ca/redis.crt').read_bytes())
        for role,service in rate_config['services'].items():
            service['network_mode']='service:net';service['depends_on']=['net'];service['pull_policy']='never'
            service['volumes']=[host(rate_root/v[2:].split(':',1)[0])+':'+v.split(':',1)[1] if v.startswith('./') else v for v in service['volumes']]
            config['services'][role]=service
        config['volumes'].update(rate_config['volumes'])
        for purpose,owner in [('app',10001),('kafka',1000)]:
            path=directory/'tls'/purpose;path.chmod(0o755)
            for file in path.iterdir():os.chown(file,owner,owner);file.chmod(0o400 if file.suffix in ('.key','.p12') else 0o444)
        admin=directory/'tls/kafka/admin.properties'
        admin.write_text('security.protocol=SSL\nssl.keystore.type=PKCS12\nssl.keystore.location=/tls/kafka.p12\nssl.keystore.password='+secret['kafka_tls_password']+'\nssl.truststore.type=PKCS12\nssl.truststore.location=/tls/kafka-trust.p12\nssl.truststore.password='+secret['kafka_tls_password']+'\nssl.endpoint.identification.algorithm=https\ndefault.api.timeout.ms=5000\nrequest.timeout.ms=3000\n')
        os.chown(admin,1000,1000);admin.chmod(0o400)
        shutil.copy(ROOT/'app-entrypoint.sh',directory/'app-entrypoint.sh');(directory/'app-entrypoint.sh').chmod(0o444)
        caddy=(ROOT/'Caddyfile').read_text();prefix,_,site=caddy.partition('{$TLS_DOMAIN} {');prefix=prefix.replace('{\n','{\n    auto_https off\n',1)
        start=site.index('    tls {');end=site.index('    request_body {');site=site[:start]+'    tls /ingress/server.crt /ingress/server.key\n'+site[end:]
        (directory/'Caddyfile').write_text(prefix+'{$TLS_DOMAIN} {'+site);(directory/'health').mkdir(exist_ok=True)
        for name in ('app','proxy','migrate','kafka'):
            service=full['services'][name];service['network_mode']='service:net';service['depends_on']=['net'];service['pull_policy']='never'
            service['volumes']=[host(directory/v[2:].split(':',1)[0])+':'+v.split(':',1)[1] if v.startswith('./') else v for v in service.get('volumes',[])]
            if name=='proxy':
                service['environment']['TLS_DOMAIN']=vip;service['volumes'].append(host(work/'ingress')+':/ingress:ro')
            config['services'][name]=service
        (directory/'compose.yaml').write_text(json.dumps(config))
        run(command+['up','-d','kafka','redis','sentinel'])
    kafka=out(projects[0]+['ps','-q','kafka'])
    def kafka_cli(tool,*args):
        return out(['docker','exec','-e','KAFKA_HEAP_OPTS=-Xms32m -Xmx128m',kafka,'/opt/kafka/bin/'+tool,'--bootstrap-server',','.join(n['private_ip']+':9092' for n in nodes),'--command-config','/tls/admin.properties',*args])
    wait(lambda:kafka_cli('kafka-topics.sh','--list') is not None,'Kafka quorum did not become ready')
    for topic in ('notification-events-v1','notification-deliveries-dlq-v1'):
        kafka_cli('kafka-topics.sh','--create','--if-not-exists','--topic',topic,'--partitions','6','--replication-factor','3','--config','min.insync.replicas=2','--config','unclean.leader.election.enable=false')
        kafka_cli('kafka-acls.sh','--add','--allow-principal','User:CN=notification-service','--operation','Read','--operation','Write','--operation','Describe','--topic',topic)
    kafka_cli('kafka-acls.sh','--add','--allow-principal','User:CN=notification-service','--operation','Read','--group','notification-dispatch-v1')
    kafka_cli('kafka-acls.sh','--add','--allow-principal','User:CN=notification-service','--operation','IdempotentWrite','--cluster')
    description=kafka_cli('kafka-topics.sh','--describe','--topic','notification-events-v1')
    assert 'ReplicationFactor: 3' in description
    checks['three_replica_mutual_tls_kafka']=True
    run(projects[0]+['run','--rm','--no-deps','migrate'])
    grants=(ROOT.parent/'droplet/grants.sql').read_text()
    run(['docker','exec','-i',client,'psql',primary_dsn,'-v','ON_ERROR_STOP=1'],input=grants,text=True,stdout=subprocess.DEVNULL)
    for command in projects:run(command+['up','-d','app','proxy'])
    config=work/'haproxy.cfg';config.write_text('global\n  log stdout format raw local0\ndefaults\n  mode tcp\n  timeout connect 3s\n  timeout client 15s\n  timeout server 15s\nlisten notifications\n  bind :443\n  balance roundrobin\n  option httpchk GET /health/ready\n  http-check expect status 200\n'+''.join('  server node'+str(n['id'])+' '+n['private_ip']+':443 check port 9080 inter 1s fall 2 rise 2\n' for n in nodes));config.chmod(0o444)
    run(['docker','run','-d','--name',frontend,'--network','notification-ha-ci','--ip',vip,'-v',host(config)+':/usr/local/etc/haproxy/haproxy.cfg:ro',os.environ.get('HA_FRONTEND_IMAGE','public.ecr.aws/docker/library/haproxy:3.2-alpine')])
    tls=ssl.create_default_context(cafile=str(work/'pki/offline-ca/postgres.crt'))
    def api(path,data=None,key=None,token=True):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+secret['api_token']
        if key:headers['Idempotency-Key']=key
        request=urllib.request.Request('https://'+vip+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
        try:
            with urllib.request.urlopen(request,context=tls,timeout=10) as response:
                body=response.read();return response.status,json.loads(body) if body else None
        except urllib.error.HTTPError as error:return error.code,None
        except (urllib.error.URLError, OSError, http.client.HTTPException):return 0,None
    try:
        wait(lambda:api('/health/ready',token=False)[0]==200,'Load-balanced HTTPS did not become ready')
        checks['trusted_local_https_and_load_balancing']=True
        assert api('/v1/subscriptions',token=False)[0]==401
        def publish(index,matching=False):
            data={'type':'ha.local.delivery' if matching else 'ha.local.ingest','source':'assessment-local-ha','payload':{'synthetic':True,'index':index,'run':'RANCHER-HA'}}
            result=wait(lambda:(value if (value:=api('/v1/events',data,key='local-ha-'+str(index)))[0]==202 else None),'Event was not accepted after failover')
            event=result[1]['event']['id'];report['acknowledged_event_ids'].append(event);return event
        receiver=os.environ.get('HA_TEST_WEBHOOK_URL');delivered=[]
        if receiver:
            status,subscription=api('/v1/subscriptions',{'webhookUrl':receiver,'filter':{'type':'ha.local.delivery','source':'assessment-local-ha'}});assert status==201
            for index in range(3):
                event=publish('visible-'+str(index),True)
                result=wait(lambda:next((x for x in (api('/v1/events/'+event+'/deliveries')[1] or {}).get('items',[]) if x['status']=='delivered'),None),'Synthetic webhook was not delivered')
                assert result['attempts'][-1]['httpStatus']==204;delivered.append({'event_id':event,'delivery_id':result['id'],'http_status':204})
        for index in range(100):publish(index)
        print('Accepted 100 events through three TLS application replicas',flush=True)
        leader=next(m['name'] for m in context['cluster']() if m['role'] in ('leader','primary'));victim=int(leader[-1]);start=time.monotonic()
        run(projects[victim-1]+['stop','--timeout','0','app','proxy','kafka','postgres','etcd'])
        publish('after-node-failure');report['single_node_recovery_seconds']=round(time.monotonic()-start,2)
        if receiver:
            event=publish('visible-during-node-failure',True)
            result=wait(lambda:next((x for x in (api('/v1/events/'+event+'/deliveries')[1] or {}).get('items',[]) if x['status']=='delivered'),None),'Kafka fanout did not deliver with one whole node unavailable')
            assert result['attempts'][-1]['httpStatus']==204
            delivered.append({'event_id':event,'delivery_id':result['id'],'http_status':204,'phase':'single-node-down'})
            checks['kafka_fanout_and_webhook_delivery_with_one_node_down']=True
        for event in report['acknowledged_event_ids']:assert api('/v1/events/'+event)[0]==200,event
        checks['whole_simulated_node_loss_preserves_every_acknowledged_event']=True
        # Recover one node before testing quorum loss, then deliberately leave only one running node.
        run(projects[victim-1]+['up','-d','etcd','postgres','kafka','app','proxy'])
        wait(lambda:len([m for m in context['cluster']() if m['state'] in ('running','streaming')])==3,'Database member did not rejoin')
        for command in projects[:2]:run(command+['stop','--timeout','0','app','proxy','kafka','postgres','etcd'])
        status,_=api('/v1/events',{'type':'ha.local.ingest','source':'assessment-local-ha','payload':{'synthetic':True}},key='must-not-ack-without-quorum')
        assert status!=202,status;checks['quorum_loss_refuses_unsafe_acknowledgment']=True
        for command in projects[:2]:run(command+['up','-d','etcd','postgres','kafka','app','proxy'])
        publish('after-quorum-recovery')
        for event in report['acknowledged_event_ids']:assert api('/v1/events/'+event)[0]==200,event
        checks['quorum_recovery_preserves_acknowledged_events']=True
        backup_env={'NOTIFICATION_HA_LOCAL_TEST':'true','PGHOST':addresses,'PGPORT':'5432','PGUSER':'notification_backup','PGPASSWORD':secret['backup_password'],'PGDATABASE':'notifications','PGSSLMODE':'verify-full','PGSSLROOTCERT':'/tls/postgres-ca.crt','PGTARGETSESSIONATTRS':'read-write','PG_ADMIN_DSN':primary_dsn,'PG_ADMIN_PASSWORD':secret['pg_owner_password'],'PG_BACKUP_DSN':primary_dsn.replace('user=notification_owner','user=notification_backup').replace('password='+secret['pg_owner_password'],'password='+secret['backup_password'])}
        command=['docker','exec','-i']
        for key,value in backup_env.items():command+=['-e',key+'='+value]
        command += [client,'python3','-']
        completed=run(command,input=(ROOT/'test-backup-roundtrip.py').read_text(),text=True,capture_output=True)
        report['backup_roundtrip']=json.loads(completed.stdout);checks['encrypted_backup_restores_every_table']=True
        for command in projects:
            logs=out(command+['logs','--no-color','proxy'])
            assert secret['caddy_password'] not in logs, 'Proxy logs disclosed database credential'
        checks['proxy_logs_do_not_disclose_database_credentials']=True
        report['delivered_demo_events']=delivered;report['acknowledged_count']=len(report['acknowledged_event_ids'])
        output=ROOT.parent.parent/'.deploy/local-ha-result.json';output.write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
    finally:subprocess.run(['docker','rm','-f',frontend],check=False)

if __name__=='__main__':
    if os.environ.get('NOTIFICATION_HA_LOCAL_TEST')!='true':raise SystemExit('Explicit local Rancher mode required')
    (ROOT.parent.parent/'.deploy/local-ha-result.json').unlink(missing_ok=True)
    database=load('database_drill','test-postgres-cluster.py');database.main(extra_validation=validate)
