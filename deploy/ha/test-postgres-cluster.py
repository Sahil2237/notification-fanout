#!/usr/bin/env python3
"""Disposable GitHub runner: real three-member TLS etcd/Patroni failover, no host watchdog."""
import importlib.util,json,os,pathlib,shutil,subprocess,tempfile,time,urllib.request,ssl
ROOT=pathlib.Path(__file__).resolve().parent

def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def output(args):return subprocess.check_output(args,text=True).strip()
def main(extra_validation=None):
    local=os.environ.get('NOTIFICATION_HA_LOCAL_TEST')=='true'
    if local:
        if output(['docker','info','--format','{{.Name}}'])!='lima-rancher-desktop':raise SystemExit('Local mode is restricted to Rancher Desktop')
    elif os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('Disposable GitHub runner or explicit Rancher local test only')
    tools=os.environ['HA_TOOLS_IMAGE'];etcd=os.environ.get('ETCD_IMAGE','gcr.io/etcd-development/etcd:v3.7.4')
    spec=importlib.util.spec_from_file_location('render',ROOT/'render.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    base=ROOT.parent.parent/'.deploy/local-ha' if local else None
    if base:base.mkdir(parents=True,exist_ok=True)
    work=pathlib.Path(tempfile.mkdtemp(prefix='ha-ci-',dir=base));projects=[]
    host_root=output(['docker','volume','inspect','notification-workspaces','--format','{{.Mountpoint}}']) if local else None
    def host_path(path):return host_root+str(path)[len('/workspaces'):] if local else str(path)
    inv={'nodes':[{'id':i,'private_ip':'172.29.0.'+str(10+i)} for i in (1,2,3)],'tls_domain':'203.0.113.8','app_image':os.environ.get('HA_APP_IMAGE','notification-ci:unused'),'tools_image':tools,'caddy_image':os.environ.get('HA_PROXY_IMAGE','caddy:2.11.9-alpine'),'etcd_image':etcd,'kafka_image':os.environ.get('HA_KAFKA_IMAGE','apache/kafka:4.3.2')}
    secrets={key:'ci-'+key+'-abcdefghijklmnopqrstuvwxyz0123456789' for key in ('pg_owner_password','replication_password','db_password','admin_token','api_token','signing_secret','caddy_password','backup_password','kafka_cluster_id','kafka_tls_password')}|{'webhook_origins':['https://example.com']}
    import base64,uuid
    secrets['kafka_cluster_id']=base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip('=')
    if os.environ.get('HA_TEST_WEBHOOK_URL'):
        from urllib.parse import urlsplit
        receiver=urlsplit(os.environ['HA_TEST_WEBHOOK_URL']);assert receiver.scheme=='https'
        secrets['webhook_origins']=[receiver.scheme+'://'+receiver.netloc]
    (work/'kafka-password').write_text(secrets['kafka_tls_password'])
    (work/'inventory.json').write_text(json.dumps(inv));(work/'secrets.json').write_text(json.dumps(secrets))
    run(['python3',str(ROOT/'create-pki.py'),str(work/'inventory.json'),str(work/'pki')]+(['--kafka-password-file',str(work/'kafka-password')] if extra_validation else []))
    run(['docker','network','create','--subnet','172.29.0.0/24','notification-ha-ci'])
    try:
        for node in inv['nodes']:
            i=node['id'];directory=work/('node'+str(i));directory.mkdir();shutil.copytree(work/'pki'/('node'+str(i))/'tls',directory/'tls')
            compose,pg=module.render(inv,secrets,i)
            # This test override is not packaged. Three containers cannot independently fence one shared CI host.
            pg['watchdog']['mode']='off'
            for purpose in ('postgres','control'):
                for path in (directory/'tls'/purpose).iterdir():
                    os.chown(path,999,999);path.chmod(0o400 if path.suffix=='.key' else 0o444)
                (directory/'tls'/purpose).chmod(0o755)
            for key in ('app','proxy','migrate','kafka'):compose['services'].pop(key)
            compose['services']['net']={'image':os.environ.get('HA_NETWORK_IMAGE','alpine:3.23'),'command':['sleep','infinity'],'networks':{'test':{'ipv4_address':node['private_ip']}}}
            compose['networks']={'test':{'external':True,'name':'notification-ha-ci'}}
            for name in ('etcd','postgres'):
                compose['services'][name]['network_mode']='service:net'
                compose['services'][name]['depends_on']=['net']
            compose['services']['postgres'].pop('devices')
            if local:
                for service in compose['services'].values():
                    service['pull_policy']='never'
                    service['volumes']=[host_path(directory/value[2:].split(':',1)[0])+':'+value.split(':',1)[1] if value.startswith('./') else value for value in service.get('volumes',[])]
            (directory/'patroni.json').write_text(json.dumps(pg));os.chown(directory/'patroni.json',999,999);(directory/'patroni.json').chmod(0o400)
            (directory/'compose.yaml').write_text(json.dumps(compose));command=['docker','compose','-p','notification-ha-ci'+str(i),'-f',str(directory/'compose.yaml')];projects.append(command)
            run(command+['up','-d'])
        def cluster():
            for node in inv['nodes']:
                tls=work/'pki'/('node'+str(node['id']))/'tls/control'
                context=ssl.create_default_context(cafile=str(tls/'control-ca.crt'));context.load_cert_chain(str(tls/'control.crt'),str(tls/'control.key'))
                try:
                    with urllib.request.urlopen('https://'+node['private_ip']+':8008/cluster',context=context,timeout=2) as response:return json.load(response)['members']
                except Exception:pass
            return []
        def await_ready(previous=None):
            for attempt in range(120):
                members=cluster();leaders=[m for m in members if m['role'] in ('leader','primary') and m['state']=='running' and m['name']!=previous]
                if leaders and any(m['role']=='sync_standby' and m['state'] in ('running','streaming') for m in members):return leaders[0]['name']
                time.sleep(1)
            raise RuntimeError('Synchronous cluster not ready')
        leader=await_ready();controller=2 if leader!='node2' else 3
        client=output(projects[controller-1]+['ps','-q','postgres'])
        def sql(statement):
            dsn='host='+','.join(n['private_ip'] for n in inv['nodes'])+' port=5432 user=notification_owner password='+secrets['pg_owner_password']+' dbname=postgres sslmode=verify-full sslrootcert=/tls/postgres-ca.crt target_session_attrs=read-write connect_timeout=3'
            code='import json,psycopg2; c=psycopg2.connect('+repr(dsn)+'); c.autocommit=True; q=c.cursor(); q.execute('+repr(statement)+'); print(json.dumps(q.fetchall() if q.description else [])); c.close()'
            return json.loads(output(['docker','exec',client,'python3','-c',code]))
        sql('CREATE TABLE ha_acknowledged_events (id integer PRIMARY KEY)')
        for i in range(100):sql('INSERT INTO ha_acknowledged_events VALUES ('+str(i)+')')
        def replica_probe(unavailable=False):
            jar=os.environ.get('HA_APPLICATION_JAR')
            if not jar:return None
            java_image=os.environ.get('HA_JAVA_IMAGE','public.ecr.aws/docker/library/eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c')
            # Share an existing test node's persistent network namespace. Its exact peer address
            # is already in pg_hba; a random Docker client address must remain unauthorized.
            probe_network='container:'+output(projects[controller-1]+['ps','-q','net'])
            command=['docker','run','--rm','--network',probe_network,'--memory','384m','--read-only','--tmpfs','/tmp:rw,nosuid,nodev,size=64m',
                '-e','PGPROBE_HOSTS='+','.join(n['private_ip'] for n in inv['nodes']),'-e','PGPROBE_PASSWORD='+secrets['pg_owner_password'],
                '-e','PGPROBE_EXPECT_UNAVAILABLE='+str(unavailable).lower(),
                '-v',host_path(pathlib.Path(jar).resolve())+':/app/application.jar:ro',
                '-v',host_path(ROOT/'ReadReplicaProbe.java')+':/app/ReadReplicaProbe.java:ro',
                '-v',host_path(work/'pki/node1/tls/app/postgres-ca.crt')+':/tls/postgres-ca.crt:ro',
                java_image,'java','-Xlog:os+container=off','-Xmx128m','--class-path','/app/application.jar','/app/ReadReplicaProbe.java']
            return json.loads(output(command))
        read_before=replica_probe()
        expected=sql('SELECT id FROM ha_acknowledged_events ORDER BY id')
        victim=int(leader[-1]);run(projects[victim-1]+['stop','--timeout','0','postgres'])
        promoted=await_ready(previous=leader)
        actual=sql('SELECT id FROM ha_acknowledged_events ORDER BY id');assert expected==actual and len(actual)==100
        sql('INSERT INTO ha_acknowledged_events VALUES (100)');assert sql('SELECT count(*) FROM ha_acknowledged_events')[0][0]==101
        read_after=replica_probe()
        if os.environ.get('HA_APPLICATION_JAR'):
            replicas=[int(m['name'][-1]) for m in cluster() if m['name']!=promoted]
            for replica in replicas:run(projects[replica-1]+['stop','--timeout','0','postgres'])
            assert replica_probe(unavailable=True)['primary_fallback_refused']
            for replica in replicas:run(projects[replica-1]+['up','-d','postgres'])
            await_ready()
        run(projects[victim-1]+['up','-d','postgres'])
        if extra_validation:
            extra_validation({'work':work,'projects':projects,'inventory':inv,'secrets':secrets,'render':module.render,'host_path':host_path,'cluster':cluster,'client':client,'sql':sql})
        print(json.dumps({'initial_primary':leader,'promoted_primary':promoted,'acknowledged_rows_preserved':100,'new_primary_writable':True,'java_replica_read_before_failover':read_before,'java_replica_read_after_failover':read_after,'watchdog_scope':'CI override only; production renderer requires hardware/software watchdog'}))
    finally:
        for command in projects:
            subprocess.run(command+['logs','--tail','30'],check=False)
            subprocess.run(command+['down','--volumes'],check=False)
        subprocess.run(['docker','network','rm','notification-ha-ci'],check=False);shutil.rmtree(work)
if __name__=='__main__':main()
