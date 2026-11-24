#!/usr/bin/env python3
"""Render exactly three hosts. Secrets are read from a root-only JSON file, never printed."""
import argparse,ipaddress,json,pathlib,re

def validate(inventory):
    nodes=inventory['nodes']
    if len(nodes)!=3 or {n['id'] for n in nodes}!={1,2,3}:raise ValueError('Exactly three distinct node ids are required')
    if len({n['private_ip'] for n in nodes})!=3:raise ValueError('Node addresses must be distinct')
    for n in nodes:
        ip=ipaddress.ip_address(n['private_ip'])
        if ip.version!=4 or not ip.is_private or ip.is_loopback:raise ValueError('Use private VPC IPv4 addresses')
    for field in ('app_image','tools_image','caddy_image','etcd_image','kafka_image'):
        if not re.fullmatch(r'[a-zA-Z0-9./:_@-]+',inventory[field]):raise ValueError('Invalid image reference')
    if not re.fullmatch(r'[a-zA-Z0-9.-]+',inventory['tls_domain']):raise ValueError('Invalid ingress domain or IP')

def render(inventory,secrets,node_id):
    validate(inventory);nodes=sorted(inventory['nodes'],key=lambda n:n['id']);node=next(n for n in nodes if n['id']==node_id)
    ip=node['private_ip'];name='node'+str(node_id);hosts=','.join(n['private_ip'] for n in nodes)
    for field in ('pg_owner_password','replication_password','db_password','admin_token','api_token','signing_secret','caddy_password','backup_password','kafka_cluster_id'):
        if not re.fullmatch(r'[A-Za-z0-9_-]{20,}',secrets[field]):raise ValueError('Invalid or missing secret: '+field)
    ssl={'cacert':'/tls/control-ca.crt','cert':'/tls/control.crt','key':'/tls/control.key'}
    patroni={'scope':'notification-ha-v1','namespace':'/notification/','name':name,
      'restapi':{'listen':ip+':8008','connect_address':ip+':8008','certfile':'/tls/control.crt','keyfile':'/tls/control.key','cafile':'/tls/control-ca.crt','verify_client':'required'},
      'etcd3':dict(hosts=','.join(n['private_ip']+':2379' for n in nodes),protocol='https',**ssl),
      'bootstrap':{'dcs':{'ttl':30,'loop_wait':5,'retry_timeout':5,'maximum_lag_on_failover':0,'check_timeline':True,'synchronous_mode':True,'synchronous_mode_strict':True,'synchronous_node_count':1,'failsafe_mode':False,
         'postgresql':{'use_pg_rewind':True,'use_slots':True,'parameters':{'wal_level':'replica','hot_standby':'on','max_wal_senders':10,'max_replication_slots':10,'wal_keep_size':'256MB','max_slot_wal_keep_size':'2GB','wal_log_hints':'on','fsync':'on','full_page_writes':'on','synchronous_commit':'remote_apply','shared_buffers':'128MB','max_connections':100,'password_encryption':'scram-sha-256','ssl':'on','ssl_cert_file':'/tls/postgres.crt','ssl_key_file':'/tls/postgres.key','ssl_ca_file':'/tls/postgres-ca.crt'}}},
        'initdb':[{'encoding':'UTF8'},'data-checksums'],
        'pg_hba':['local all all trust']+['hostssl replication replicator '+n['private_ip']+'/32 scram-sha-256' for n in nodes]+['hostssl all all '+n['private_ip']+'/32 scram-sha-256' for n in nodes]},
      'postgresql':{'listen':ip+':5432','connect_address':ip+':5432','data_dir':'/var/lib/postgresql/ha/data','bin_dir':'/usr/lib/postgresql/17/bin','pgpass':'/var/lib/postgresql/ha/pgpass',
        'authentication':{'superuser':{'username':'notification_owner','password':secrets['pg_owner_password']},'replication':{'username':'replicator','password':secrets['replication_password'],'sslmode':'verify-full','sslrootcert':'/tls/postgres-ca.crt'}},'parameters':{'unix_socket_directories':'/var/run/postgresql'}},
      'ctl':{'cacert':'/control/control-ca.crt','certfile':'/control/control.crt','keyfile':'/control/control.key','insecure':False},
      'watchdog':{'mode':'required','device':'/dev/watchdog','safety_margin':5}}
    # Kafka identities are distinct from the control-plane CA. App certificates have topic/group ACLs only.
    kafka={'CLUSTER_ID':secrets['kafka_cluster_id'],'KAFKA_NODE_ID':str(node_id),'KAFKA_PROCESS_ROLES':'broker,controller','KAFKA_CONTROLLER_QUORUM_VOTERS':','.join(str(n['id'])+'@'+n['private_ip']+':9093' for n in nodes),
       'KAFKA_LISTENERS':'BROKER://'+ip+':9092,CONTROLLER://'+ip+':9093','KAFKA_ADVERTISED_LISTENERS':'BROKER://'+ip+':9092','KAFKA_LISTENER_SECURITY_PROTOCOL_MAP':'BROKER:SSL,CONTROLLER:SSL','KAFKA_CONTROLLER_LISTENER_NAMES':'CONTROLLER','KAFKA_INTER_BROKER_LISTENER_NAME':'BROKER',
       'KAFKA_SSL_KEYSTORE_TYPE':'PEM','KAFKA_SSL_KEYSTORE_CERTIFICATE_CHAIN':'${KAFKA_NODE_CERT}','KAFKA_SSL_KEYSTORE_KEY':'${KAFKA_NODE_KEY}','KAFKA_SSL_TRUSTSTORE_TYPE':'PEM','KAFKA_SSL_TRUSTSTORE_CERTIFICATES':'${KAFKA_CA_CERT}','KAFKA_SSL_CLIENT_AUTH':'required','KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM':'https',
       'KAFKA_AUTHORIZER_CLASS_NAME':'org.apache.kafka.metadata.authorizer.StandardAuthorizer','KAFKA_SUPER_USERS':';'.join('User:CN=kafka-'+str(n['id']) for n in nodes),'KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND':'false',
       'KAFKA_DEFAULT_REPLICATION_FACTOR':'3','KAFKA_MIN_INSYNC_REPLICAS':'2','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR':'3','KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS':'6','KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR':'3','KAFKA_TRANSACTION_STATE_LOG_MIN_ISR':'2','KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE':'false','KAFKA_AUTO_CREATE_TOPICS_ENABLE':'false','KAFKA_LOG_DIRS':'/var/lib/kafka/data','KAFKA_LOG_RETENTION_HOURS':'168','KAFKA_LOG_RETENTION_BYTES':'134217728','KAFKA_HEAP_OPTS':'-Xms256m -Xmx512m'}
    endpoints=','.join('https://'+n['private_ip']+':2379' for n in nodes)
    db_url='jdbc:postgresql://'+','.join(n['private_ip']+':5432' for n in nodes)+'/notifications'
    env={'DB_URL':db_url,'DB_USER':'notification_runtime','DB_PASSWORD':secrets['db_password'],'API_TOKEN':secrets['api_token'],'ADMIN_TOKEN':secrets['admin_token'],'WEBHOOK_SIGNING_SECRET':secrets['signing_secret'],'WEBHOOK_ORIGINS':json.dumps(secrets['webhook_origins']),
       'KAFKA_BOOTSTRAP_SERVERS':','.join(n['private_ip']+':9092' for n in nodes),'JAVA_TOOL_OPTIONS':'-Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError'}
    common={'network_mode':'host','restart':'unless-stopped','logging':{'driver':'json-file','options':{'max-size':'10m','max-file':'3'}}}
    etcd_cmd=['etcd','--name='+name,'--data-dir=/data','--listen-client-urls=https://'+ip+':2379','--advertise-client-urls=https://'+ip+':2379','--listen-peer-urls=https://'+ip+':2380','--initial-advertise-peer-urls=https://'+ip+':2380','--initial-cluster='+','.join('node'+str(n['id'])+'=https://'+n['private_ip']+':2380' for n in nodes),'--initial-cluster-token=notification-ha-v1','--initial-cluster-state=new','--cert-file=/tls/control.crt','--key-file=/tls/control.key','--trusted-ca-file=/tls/control-ca.crt','--client-cert-auth=true','--peer-cert-file=/tls/control.crt','--peer-key-file=/tls/control.key','--peer-trusted-ca-file=/tls/control-ca.crt','--peer-client-cert-auth=true','--auto-compaction-retention=1','--quota-backend-bytes=268435456']
    compose={'name':'notification-ha','services':{
      'etcd':dict(common,image=inventory['etcd_image'],mem_limit='256m',entrypoint=['/usr/local/bin/etcd'],command=etcd_cmd[1:],volumes=['etcd:/data','./tls/control:/tls:ro']),
      'postgres':dict(common,image=inventory['tools_image'],mem_limit='640m',devices=['/dev/watchdog:/dev/watchdog'],volumes=['postgres:/var/lib/postgresql/ha','./patroni.json:/config/patroni.json:ro','./tls/postgres:/tls:ro','./tls/control:/control:ro']),
      'kafka':dict(common,image=inventory['kafka_image'],mem_limit='1152m',environment=kafka,volumes=['kafka:/var/lib/kafka/data']),
      'app':dict(common,image='${APP_IMAGE:?Set tested APP_IMAGE}',mem_limit='896m',stop_grace_period='45s',environment=env,entrypoint=['/bin/sh','/config/app-entrypoint.sh'],command=['server'],volumes=['./app-entrypoint.sh:/config/app-entrypoint.sh:ro','./tls/app:/tls:ro']),
      'proxy':dict(common,image=inventory['caddy_image'],mem_limit='160m',environment={'TLS_DOMAIN':inventory['tls_domain'],'CADDY_DATABASE_DSN':'host='+hosts+' port=5432 dbname=caddy user=caddy password='+secrets['caddy_password']+' sslmode=verify-full sslrootcert=/tls/postgres-ca.crt target_session_attrs=read-write connect_timeout=3'},volumes=['./Caddyfile:/etc/caddy/Caddyfile:ro','./tls/app/postgres-ca.crt:/tls/postgres-ca.crt:ro','caddy:/data','caddy-config:/config'])},'volumes':{x:{} for x in ('postgres','etcd','kafka','caddy','caddy-config')}}
    migrate=dict(compose['services']['app'])
    migrate.pop('restart',None)
    migrate['profiles']=['tools'];migrate['command']=['db','migrate']
    migrate['environment']=dict(env,DB_USER='notification_owner',DB_PASSWORD=secrets['pg_owner_password'])
    compose['services']['migrate']=migrate
    compose['services']['proxy']['volumes'].append('./health:/state:ro')
    # Control-plane credentials are mounted only into Patroni, never the application.
    patroni['etcd3'].update(cacert='/control/control-ca.crt',cert='/control/control.crt',key='/control/control.key')
    patroni['restapi'].update(certfile='/control/control.crt',keyfile='/control/control.key',cafile='/control/control-ca.crt')
    return compose,patroni

def main():
    p=argparse.ArgumentParser();p.add_argument('inventory');p.add_argument('secrets');p.add_argument('node_id',type=int);p.add_argument('output');a=p.parse_args()
    compose,patroni=render(json.loads(pathlib.Path(a.inventory).read_text()),json.loads(pathlib.Path(a.secrets).read_text()),a.node_id)
    output=pathlib.Path(a.output);output.mkdir(parents=True,exist_ok=True,mode=0o700)
    for name,value in [('compose.yaml',compose),('patroni.json',patroni)]:
        path=output/name;path.write_text(json.dumps(value,indent=2)+'\n');path.chmod(0o600)
if __name__=='__main__':main()
