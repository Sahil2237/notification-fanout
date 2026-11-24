#!/usr/bin/env python3
"""Disposable GitHub runner: real three-member TLS etcd/Patroni failover, no host watchdog."""
import importlib.util,json,os,pathlib,shutil,subprocess,tempfile,time,urllib.request,ssl
ROOT=pathlib.Path(__file__).resolve().parent

def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def output(args):return subprocess.check_output(args,text=True).strip()
def main():
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
    inv={'nodes':[{'id':i,'private_ip':'172.29.0.'+str(10+i)} for i in (1,2,3)],'tls_domain':'203.0.113.8','app_image':'notification-ci:unused','tools_image':tools,'caddy_image':'caddy:2.11.9-alpine','etcd_image':etcd,'kafka_image':'apache/kafka:4.3.1'}
    secrets={key:'ci-only-credential-abcdefghijklmnopqrstuvwxyz' for key in ('pg_owner_password','replication_password','db_password','admin_token','api_token','signing_secret','caddy_password','backup_password','kafka_cluster_id','kafka_tls_password')}|{'webhook_origins':['https://example.com']}
    (work/'inventory.json').write_text(json.dumps(inv));(work/'secrets.json').write_text(json.dumps(secrets))
    run(['python3',str(ROOT/'create-pki.py'),str(work/'inventory.json'),str(work/'pki')])
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
        expected=sql('SELECT id FROM ha_acknowledged_events ORDER BY id')
        victim=int(leader[-1]);run(projects[victim-1]+['stop','--timeout','0','postgres'])
        promoted=await_ready(previous=leader)
        actual=sql('SELECT id FROM ha_acknowledged_events ORDER BY id');assert expected==actual and len(actual)==100
        sql('INSERT INTO ha_acknowledged_events VALUES (100)');assert sql('SELECT count(*) FROM ha_acknowledged_events')[0][0]==101
        run(projects[victim-1]+['up','-d','postgres'])
        print(json.dumps({'initial_primary':leader,'promoted_primary':promoted,'acknowledged_rows_preserved':100,'new_primary_writable':True,'watchdog_scope':'CI override only; production renderer requires hardware/software watchdog'}))
    finally:
        for command in projects:
            subprocess.run(command+['logs','--tail','30'],check=False)
            subprocess.run(command+['down','--volumes'],check=False)
        subprocess.run(['docker','network','rm','notification-ha-ci'],check=False);shutil.rmtree(work)
if __name__=='__main__':main()
