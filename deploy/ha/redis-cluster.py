#!/usr/bin/env python3
"""Bootstrap a private three-node TLS Redis/Sentinel quota store. Never print secrets."""
import argparse,ipaddress,json,os,pathlib,re,secrets,subprocess
KEYS=('admin','limiter','replication','control','peer','discovery')

def nodes_of(inventory):
    nodes=sorted(inventory['nodes'],key=lambda n:n['id'])
    if len(nodes)!=3 or [n['id'] for n in nodes]!=[1,2,3] or len({n['private_ip'] for n in nodes})!=3:
        raise ValueError('Exactly three distinct nodes required')
    for n in nodes:
        ip=ipaddress.ip_address(n['private_ip'])
        if ip.version!=4 or not ip.is_private or ip.is_loopback:raise ValueError('Private IPv4 peer addresses required')
    return nodes

def credentials(existing=None):
    value={key:secrets.token_hex(32) for key in KEYS} if existing is None else existing
    for key in KEYS:
        if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}',value.get(key,'')):raise ValueError('Invalid Redis credential: '+key)
    if len({value[k] for k in KEYS})!=len(KEYS):raise ValueError('Redis roles require independent credentials')
    return value

def tls_settings(port):
    return ['port 0','tls-port '+str(port),'tls-cert-file /tls/redis.crt','tls-key-file /tls/redis.key',
        'tls-ca-cert-file /tls/redis-ca.crt','tls-auth-clients no','tls-replication yes','tls-protocols "TLSv1.2 TLSv1.3"']

def render(inventory,passwords,node_id,image):
    nodes=nodes_of(inventory);passwords=credentials(passwords);node=next(n for n in nodes if n['id']==node_id)
    if not re.fullmatch(r'[A-Za-z0-9./:_@-]+',image):raise ValueError('Invalid Redis image')
    ip=node['private_ip'];primary=nodes[0]['private_ip']
    common=['bind '+ip+' 127.0.0.1','protected-mode yes','daemonize no','logfile ""','maxclients 128']
    data=tls_settings(6379)+common+['dir /data','aclfile /config/users.acl','save ""','appendonly yes','appendfsync always',
        'maxmemory 64mb','maxmemory-policy noeviction','repl-backlog-size 1mb','repl-diskless-sync yes','repl-diskless-sync-delay 1',
        'min-replicas-to-write 1','min-replicas-max-lag 2','masteruser replication','masterauth '+passwords['replication'],
        'replica-announce-ip '+ip,'replica-announce-port 6379']
    if node_id!=1:data+=['replicaof '+primary+' 6379']
    acl=['user default off','user admin on >'+passwords['admin']+' ~* &* +@all',
        'user limiter on >'+passwords['limiter']+' ~notification:limits:v1:* ~notification:cache:v2:* resetchannels -@all +auth +hello +ping +eval +time +hget +hset +hdel +hlen +get +incr +incrby +zadd +zrem +zcard +zrange +zrangebyscore +del +expire +expireat +wait +role +client|setname +client|setinfo',
        'user replication on >'+passwords['replication']+' resetchannels -@all +auth +ping +psync +replconf',
        'user control on >'+passwords['control']+' &__sentinel__:hello -@all +auth +multi +slaveof +replicaof +ping +exec +subscribe +config|rewrite +role +publish +info +client|setname +client|setinfo +client|kill +script|kill']
    sentinel=tls_settings(26379)+common+['dir /data','aclfile /config/users.acl',
        'sentinel announce-ip '+ip,'sentinel announce-port 26379',
        'sentinel monitor notification-rate '+primary+' 6379 2','sentinel auth-user notification-rate control',
        'sentinel auth-pass notification-rate '+passwords['control'],'sentinel down-after-milliseconds notification-rate 5000',
        'sentinel failover-timeout notification-rate 30000','sentinel parallel-syncs notification-rate 1',
        'sentinel sentinel-user peer','sentinel sentinel-pass '+passwords['peer']]
    sentinel_acl=['user default off','user peer on >'+passwords['peer']+' ~* &* +@all',
        'user discovery on >'+passwords['discovery']+' resetchannels &+switch-master -@all +auth +hello +ping +role +command +client|getname +client|id +client|setname +client|setinfo +subscribe +sentinel|get-master-addr-by-name +sentinel|master +sentinel|myid +sentinel|replicas +sentinel|sentinels +sentinel|masters']
    service={'image':image,'user':'999:999','network_mode':'host','restart':'unless-stopped','read_only':True,
        'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'pids_limit':64,
        'logging':{'driver':'json-file','options':{'max-size':'5m','max-file':'2'}}}
    redis=dict(service,cpus=0.5,mem_limit='128m',entrypoint=['redis-server'],command=['/config/redis.conf'],
        volumes=['./redis:/config','./tls:/tls:ro','redis-data:/data'])
    monitor=dict(service,cpus=0.15,mem_limit='64m',entrypoint=['redis-server'],command=['/config/sentinel.conf','--sentinel'],
        volumes=['./sentinel:/config','./tls:/tls:ro','sentinel-data:/data'])
    compose={'name':'notification-rate','services':{'redis':redis,'sentinel':monitor},'volumes':{'redis-data':{},'sentinel-data':{}}}
    return compose,{'redis/redis.conf':data,'redis/users.acl':acl,'sentinel/sentinel.conf':sentinel,'sentinel/users.acl':sentinel_acl}

def bootstrap(inventory,passwords,node_id,image,output):
    compose,files=render(inventory,passwords,node_id,image);output=pathlib.Path(output)
    if any((output/name).exists() for name in files):raise ValueError('Refusing to overwrite persistent Redis/Sentinel configuration')
    output.mkdir(parents=True,exist_ok=True,mode=0o700)
    for name,lines in files.items():
        path=output/name;path.parent.mkdir(exist_ok=True,mode=0o700)
        path.write_text('\n'.join(lines)+'\n');path.chmod(0o600)
        os.chown(path,999,999);os.chown(path.parent,999,999)
    (output/'compose.yaml').write_text(json.dumps(compose,indent=2)+'\n');(output/'compose.yaml').chmod(0o600)

def create_pki(inventory,output):
    nodes=nodes_of(inventory);root=pathlib.Path(output);root.mkdir(parents=True,mode=0o700,exist_ok=False)
    ca=root/'offline-ca';ca.mkdir(mode=0o700)
    def run(*args):subprocess.run(['openssl',*args],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    run('req','-x509','-newkey','rsa:3072','-nodes','-keyout',str(ca/'redis.key'),'-out',str(ca/'redis.crt'),'-days','3650',
        '-subj','/CN=notification-rate-ca','-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign')
    for node in nodes:
        target=root/('node'+str(node['id']));target.mkdir(mode=0o700)
        run('req','-new','-newkey','rsa:2048','-nodes','-keyout',str(target/'redis.key'),'-out',str(target/'redis.csr'),'-subj','/CN=notification-rate-'+str(node['id']))
        ext=target/'redis.ext';ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=IP:'+node['private_ip']+',IP:127.0.0.1,DNS:localhost\n')
        run('x509','-req','-in',str(target/'redis.csr'),'-CA',str(ca/'redis.crt'),'-CAkey',str(ca/'redis.key'),'-CAcreateserial','-out',str(target/'redis.crt'),'-days','90','-sha256','-extfile',str(ext))
        (target/'redis-ca.crt').write_bytes((ca/'redis.crt').read_bytes());ext.unlink();(target/'redis.csr').unlink()
        for p in target.iterdir():os.chown(p,999,999);p.chmod(0o400 if p.suffix=='.key' else 0o444)
        os.chown(target,999,999)
    # Authority keys remain offline; deployment transfers node folders only.
    for p in ca.iterdir():p.chmod(0o600)

if __name__=='__main__':
    os.umask(0o077);p=argparse.ArgumentParser();p.add_argument('inventory');p.add_argument('output');p.add_argument('--pki',action='store_true');p.add_argument('--secrets');p.add_argument('--node',type=int);p.add_argument('--image');a=p.parse_args()
    inventory=json.loads(pathlib.Path(a.inventory).read_text())
    if a.pki:create_pki(inventory,a.output)
    else:bootstrap(inventory,json.loads(pathlib.Path(a.secrets).read_text()),a.node,a.image,a.output)
