#!/usr/bin/env python3
"""Disposable local/CI TLS Redis/Sentinel drill using the application's actual Jedis client."""
import importlib.util,json,os,pathlib,shutil,subprocess,tempfile,time,uuid
ROOT=pathlib.Path(__file__).resolve().parent
NETWORK='notification-rate-ci'

def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def out(args):return subprocess.check_output(args,text=True).strip()
def wait(check,message,seconds=70):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        try:
            value=check()
            if value:return value
        except (subprocess.CalledProcessError,ValueError,IndexError):pass
        time.sleep(1)
    raise RuntimeError(message)

def result(text):
    return json.loads(next(line[len('PROBE_RESULT '):] for line in text.splitlines() if line.startswith('PROBE_RESULT ')))

def main():
    local=os.environ.get('NOTIFICATION_HA_LOCAL_TEST')=='true'
    if local:
        if out(['docker','info','--format','{{.Name}}'])!='lima-rancher-desktop':raise SystemExit('Rancher Desktop only')
    elif os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('Explicit Rancher test or disposable CI runner only')
    if os.geteuid()!=0:raise SystemExit('Disposable fixture needs root to set container file ownership')
    spec=importlib.util.spec_from_file_location('redis_cluster',ROOT/'redis-cluster.py');rate=importlib.util.module_from_spec(spec);spec.loader.exec_module(rate)
    parent=ROOT.parent.parent/'.deploy';parent.mkdir(exist_ok=True)
    work=pathlib.Path(tempfile.mkdtemp(prefix='redis-ci-',dir=parent))
    volume=out(['docker','volume','inspect','notification-workspaces','--format','{{.Mountpoint}}']) if local else None
    def host(path):return volume+str(path)[len('/workspaces'):] if local else str(path)
    image=os.environ.get('REDIS_IMAGE','public.ecr.aws/docker/library/redis:8.10.3@sha256:04d1eefe18169c3349884dc2a552f6c429d9a9d8eda4caab13d2b826dc6dfc4c')
    java=os.environ.get('HA_JAVA_IMAGE','public.ecr.aws/docker/library/eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c')
    jar=pathlib.Path(os.environ['HA_APPLICATION_JAR']).resolve()
    inventory={'nodes':[{'id':i,'private_ip':'172.30.17.'+str(10+i)} for i in (1,2,3)]}
    secret=rate.credentials();rate.create_pki(inventory,work/'pki')
    settings={'sentinels':[n['private_ip']+':26379' for n in inventory['nodes']],'user':'limiter','password':secret['limiter'],
        'sentinelUser':'discovery','sentinelPassword':secret['discovery'],'tls':True,'requiredReplicas':1,
        'caCertificate':(work/'pki/offline-ca/redis.crt').read_text()}
    (work/'settings.json').write_text(json.dumps(settings));(work/'settings.json').chmod(0o600)
    state=work/'state';state.mkdir(mode=0o777)
    names=[];report={'checks':{}};checks=report['checks'];watch=None
    run(['docker','network','create','--subnet','172.30.17.0/24',NETWORK],stdout=subprocess.DEVNULL)
    def name(i,role):return 'notification-rate-ci-'+str(i)+'-'+role
    def cli(i,role,*command,user=None,raw=False):
        user=user or ('admin' if role=='redis' else 'peer');password=secret[user]
        args=['docker','exec','-e','REDISCLI_AUTH='+password,name(i,role),'redis-cli','--tls','--cacert','/tls/redis-ca.crt',
            '-h','127.0.0.1','-p','6379' if role=='redis' else '26379','--user',user,'--no-auth-warning','--raw' if raw else '--json',*command]
        value=out(args)
        return value if raw else json.loads(value)
    def master():
        value=cli(2,'sentinel','SENTINEL','get-master-addr-by-name','notification-rate')
        return next(n['id'] for n in inventory['nodes'] if n['private_ip']==value[0])
    def replicas(i):return len(cli(i,'redis','ROLE')[2])
    def probe(mode,popen=False):
        command=['docker','run','--rm','--name','notification-rate-ci-probe','--network',NETWORK,'--memory','384m','--cpus','1','--read-only','--tmpfs','/tmp:rw,nosuid,nodev,size=64m',
            '-v',host(jar)+':/app/application.jar:ro','-v',host(ROOT/'RedisHaProbe.java')+':/app/RedisHaProbe.java:ro',
            '-v',host(work/'settings.json')+':/settings.json:ro','-v',host(state)+':/state',
            '--entrypoint','java',java,'-Xlog:os+container=off','-Xmx128m','--class-path','/app/application.jar','/app/RedisHaProbe.java','/settings.json','/state',mode]
        if popen:return subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        return result(out(command))
    try:
        for node in inventory['nodes']:
            i=node['id'];directory=work/('node'+str(i));rate.bootstrap(inventory,secret,i,image,directory)
            shutil.copytree(work/'pki'/('node'+str(i)),directory/'tls');os.chown(directory/'tls',999,999)
            for file in (directory/'tls').iterdir():os.chown(file,999,999)
            for role in ('redis','sentinel'):
                address=node['private_ip'] if role=='redis' else '172.30.17.'+str(20+i)
                # Sentinel has a separate container IP in this disposable fixture; production shares one host IP.
                if role=='sentinel':
                    path=directory/'sentinel/sentinel.conf';value=path.read_text().replace('bind '+node['private_ip']+' 127.0.0.1','bind 0.0.0.0').replace('sentinel announce-ip '+node['private_ip'],'sentinel announce-ip '+address);path.write_text(value)
                    # Node cert includes Redis host IP: both disposable services share that network namespace below.
                args=['docker','run','-d','--name',name(i,role),'--memory','128m' if role=='redis' else '64m','--read-only','--user','999:999',
                    '--cap-drop','ALL','--security-opt','no-new-privileges','--pids-limit','64',
                    '-v',host(directory/role)+':/config','-v',host(directory/'tls')+':/tls:ro','--tmpfs','/data:rw,uid=999,gid=999,size=32m']
                if role=='redis':args+=['--network',NETWORK,'--ip',node['private_ip']]+(['--network-alias','wrong-redis-host'] if i==1 else [])
                else:args+=['--network','container:'+name(i,'redis')]
                # Sharing Redis network identity keeps TLS SANs and advertised addresses identical to production.
                if role=='sentinel':
                    path=directory/'sentinel/sentinel.conf';path.write_text(path.read_text().replace('sentinel announce-ip '+address,'sentinel announce-ip '+node['private_ip']))
                args+=['--entrypoint','redis-server',image,'/config/'+('redis.conf' if role=='redis' else 'sentinel.conf')]+(['--sentinel'] if role=='sentinel' else [])
                run(args,stdout=subprocess.DEVNULL);names.append(name(i,role))
        wait(lambda:replicas(1)==2,'Redis replicas did not connect')
        wait(lambda:len(cli(2,'sentinel','SENTINEL','sentinels','notification-rate'))==2,'Sentinels did not discover peers')
        checks.update(probe('shared'));checks.update(probe('hostname'))
        denied=cli(1,'redis','FLUSHALL',user='limiter',raw=True)
        if 'NOPERM' not in denied:raise AssertionError('Limiter may flush Redis')
        denied=cli(2,'sentinel','SENTINEL','FAILOVER','notification-rate',user='discovery',raw=True)
        if 'NOPERM' not in denied:raise AssertionError('Discovery identity may force failover')
        checks['application_acl_cannot_administer_data_or_failover']=True
        watch=probe('follow',popen=True);wait(lambda:(state/'ready').exists(),'Persistent Java client did not become ready')
        run(['docker','stop','--time','0',name(1,'sentinel'),name(1,'redis')],stdout=subprocess.DEVNULL)
        (state/'stopped').write_text('stopped')
        promoted=wait(lambda:(m if (m:=master())!=1 and cli(m,'redis','ROLE')[0]=='master' and replicas(m)>=1 else None),'Sentinel automatic failover failed')
        stdout,stderr=watch.communicate(timeout=90)
        if watch.returncode:raise AssertionError('Persistent Jedis client did not recover after failover')
        checks.update(result(stdout));report['promoted_node']=promoted
        other=next(n['id'] for n in inventory['nodes'] if n['id'] not in (1,promoted))
        run(['docker','stop','--time','0',name(other,'sentinel'),name(other,'redis')],stdout=subprocess.DEVNULL)
        time.sleep(3);checks.update(probe('unavailable'))
        for i in (1,other):run(['docker','start',name(i,'redis'),name(i,'sentinel')],stdout=subprocess.DEVNULL)
        wait(lambda:replicas(promoted)==2,'Redis nodes did not rejoin after restart')
        checks['replicas_rejoined_after_failover']=True
        print(json.dumps(report))
    finally:
        if watch and watch.poll() is None:watch.terminate();watch.wait(timeout=10)
        subprocess.run(['docker','rm','-f','notification-rate-ci-probe'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for item in reversed(names):subprocess.run(['docker','rm','-f',item],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','network','rm',NETWORK],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        shutil.rmtree(work)
if __name__=='__main__':os.umask(0o077);main()
