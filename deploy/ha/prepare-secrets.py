#!/usr/bin/env python3
"""Preserve deployed API/webhook identity while creating new infrastructure credentials."""
import json,os,pathlib,re,secrets,sys

def build(old,kafka_tls_password):
    result={new:old[previous] for new,previous in {'pg_owner_password':'PG_OWNER_PASSWORD','db_password':'DB_PASSWORD','admin_token':'ADMIN_TOKEN','api_token':'API_TOKEN','signing_secret':'WEBHOOK_SIGNING_SECRET'}.items()}
    for value in [*result.values(),kafka_tls_password]:
        if not re.fullmatch(r'[A-Za-z0-9_-]{32,}',value):raise ValueError('Existing identity or keystore credential is unsafe')
    result['webhook_origins']=json.loads(old['WEBHOOK_ORIGINS']);result['kafka_tls_password']=kafka_tls_password
    for key in ('replication_password','caddy_password','backup_password','backup_manifest_key'):result[key]=secrets.token_hex(32)
    import base64,uuid
    result['kafka_cluster_id']=base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip('=')
    return result

def main():
    if os.geteuid()!=0:raise SystemExit('Existing server operator only')
    root=pathlib.Path('/opt/notification-fanout');path=root/'ha/secrets.json'
    if path.exists():print('Preserved existing HA credentials');return
    password=pathlib.Path(sys.argv[1]).read_text().strip()
    old=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
    result=build(old,password);os.umask(0o077)
    with path.open('x') as target:target.write(json.dumps(result,indent=2)+'\n')
    print('Prepared root-only infrastructure credentials; deployed API and signing identity preserved')
if __name__=='__main__':main()
