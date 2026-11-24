#!/usr/bin/env python3
"""Daily encrypted PostgreSQL snapshot in one private Spaces prefix; 24-hour retention."""
import argparse,datetime,hashlib,hmac,json,os,pathlib,re,subprocess,tarfile,tempfile,uuid
UTC=datetime.timezone.utc
PREFIX='postgres/daily/v1/'
RETENTION=datetime.timedelta(days=1)
NAME=re.compile(r'^postgres/daily/v1/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}\.(?:tar\.age|json)$')
TABLES=('events','subscriptions','deliveries','delivery_attempts','event_outbox','approved_webhook_origins','webhook_origin_audit','kafka_dead_letters','databasechangelog')

def candidates(client,bucket):
    return [obj for page in client.get_paginator('list_objects_v2').paginate(Bucket=bucket,Prefix=PREFIX) for obj in page.get('Contents',[]) if NAME.fullmatch(obj['Key'])]

def expired(items,now):
    return [obj['Key'] for obj in items if now-obj['LastModified']>=RETENTION]

def prune(client,bucket,items,now):
    keys=expired(items,now)
    for key in keys:client.delete_object(Bucket=bucket,Key=key)
    return len(keys)

def digest(path):
    with pathlib.Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def due(items,now):
    # Check often to recover missed timers/node failures, create at most one scheduled backup per UTC day.
    recent=[obj for obj in items if obj['Key'].endswith('.json') and now-obj['LastModified']<RETENTION]
    if not recent:return True
    return now.hour>=2 and not any(obj['LastModified'].astimezone(UTC).date()==now.date() for obj in recent)

def seal_manifest(value,key):
    if len(key)<32:raise ValueError('Use a separate strong backup manifest key')
    unsigned={k:v for k,v in value.items() if k!='hmac_sha256'}
    signature=hmac.new(key.encode(),json.dumps(unsigned,sort_keys=True,separators=(',',':')).encode(),hashlib.sha256).hexdigest()
    return dict(unsigned,hmac_sha256=signature)

def verify_manifest(value,key):
    expected=seal_manifest(value,key)['hmac_sha256']
    if not hmac.compare_digest(value.get('hmac_sha256',''),expected):raise ValueError('Backup manifest authentication failed')
    return safe_manifest(value)

def safe_manifest(value):
    if value.get('schema_version')!=1 or not NAME.fullmatch(value.get('object_key','')) or not value['object_key'].endswith('.tar.age'):
        raise ValueError('Invalid backup manifest')
    if not re.fullmatch(r'[0-9a-f]{64}',value.get('sha256','')) or not isinstance(value.get('bytes'),int) or value['bytes']<1:
        raise ValueError('Invalid backup integrity metadata')
    return value

def fetch(client,bucket,manifest_key,destination,identity):
    if not NAME.fullmatch(manifest_key) or not manifest_key.endswith('.json'):raise ValueError('Invalid manifest key')
    manifest=verify_manifest(json.loads(client.get_object(Bucket=bucket,Key=manifest_key)['Body'].read()),os.environ['BACKUP_MANIFEST_KEY'])
    output=pathlib.Path(destination)
    if output.exists():raise ValueError('Restore output must be a new directory')
    output.mkdir(mode=0o700,parents=True)
    with tempfile.TemporaryDirectory(prefix='notification-restore-') as work:
        work=pathlib.Path(work);cipher=work/'backup.age';archive=work/'backup.tar'
        client.download_file(bucket,manifest['object_key'],str(cipher))
        if cipher.stat().st_size!=manifest['bytes'] or digest(cipher)!=manifest['sha256']:raise ValueError('Backup checksum mismatch')
        with archive.open('wb') as target:subprocess.run(['age','--decrypt','-i',identity,str(cipher)],stdout=target,check=True)
        with tarfile.open(archive) as bundle:
            members=bundle.getmembers()
            if {member.name for member in members}!={'database.dump','snapshot.json'} or len(members)!=2 or any(not member.isfile() for member in members):raise ValueError('Unexpected backup archive members')
            for member in members:
                with bundle.extractfile(member) as source,(output/member.name).open('xb') as target:
                    import shutil
                    shutil.copyfileobj(source,target)
                (output/member.name).chmod(0o600)
        snapshot=json.loads((output/'snapshot.json').read_text())
        if digest(output/'database.dump')!=snapshot['dump_sha256']:raise ValueError('Dump checksum mismatch')
    return manifest

def backup(client,bucket,connection,now,recipient):
    # Export one repeatable-read snapshot for both pg_dump and verification counts.
    connection.set_session(isolation_level="REPEATABLE READ",readonly=True,autocommit=False)
    with connection.cursor() as cursor:
        cursor.execute('SELECT pg_export_snapshot()');snapshot_id=cursor.fetchone()[0]
        counts={}
        for table in TABLES:
            cursor.execute('SELECT count(*) FROM '+table);counts[table]=cursor.fetchone()[0]
        with tempfile.TemporaryDirectory(prefix='notification-backup-') as work:
            work=pathlib.Path(work);dump=work/'database.dump';archive=work/'backup.tar';cipher=work/'backup.tar.age'
            subprocess.run(['pg_dump','--format=custom','--no-owner','--no-acl','--snapshot='+snapshot_id,'--file='+str(dump)],check=True)
            metadata={'schema_version':1,'created_at':now.isoformat(),'row_counts':counts,'dump_sha256':digest(dump),'database_major':17}
            (work/'snapshot.json').write_text(json.dumps(metadata,sort_keys=True))
            with tarfile.open(archive,'w') as bundle:
                for name in ('database.dump','snapshot.json'):bundle.add(work/name,arcname=name)
            with cipher.open('wb') as target:subprocess.run(['age','--encrypt','-r',recipient,str(archive)],stdout=target,check=True)
            key=PREFIX+now.strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex+'.tar.age'
            sha=digest(cipher);size=cipher.stat().st_size
            client.upload_file(str(cipher),bucket,key,ExtraArgs={'ACL':'private','ContentType':'application/octet-stream','Metadata':{'sha256':sha}})
            head=client.head_object(Bucket=bucket,Key=key)
            if head['ContentLength']!=size or head.get('Metadata',{}).get('sha256')!=sha:raise ValueError('Uploaded backup metadata mismatch')
            # Publish the manifest last: an interrupted upload must not advertise a successful backup.
            manifest=seal_manifest({'schema_version':1,'object_key':key,'sha256':sha,'bytes':size,'created_at':now.isoformat()},os.environ['BACKUP_MANIFEST_KEY'])
            manifest_key=key.removesuffix('.tar.age')+'.json'
            client.put_object(Bucket=bucket,Key=manifest_key,Body=json.dumps(manifest).encode(),ACL='private',ContentType='application/json')
            return manifest_key

def main():
    p=argparse.ArgumentParser();p.add_argument('command',choices=['run','fetch']);p.add_argument('--manifest');p.add_argument('--output');p.add_argument('--identity');a=p.parse_args()
    import boto3,psycopg2
    from botocore.config import Config
    endpoint=os.environ['SPACES_ENDPOINT'];bucket=os.environ['SPACES_BUCKET']
    if not re.fullmatch(r'https://[a-z0-9-]+\.digitaloceanspaces\.com',endpoint):raise ValueError('Use a verified regional Spaces endpoint')
    if not os.environ.get('AWS_ACCESS_KEY_ID') or not os.environ.get('AWS_SECRET_ACCESS_KEY'):raise ValueError('Explicit scoped Spaces credentials are required')
    client=boto3.client('s3',endpoint_url=endpoint,region_name=os.environ['SPACES_REGION'],config=Config(signature_version='s3v4',retries={'max_attempts':5,'mode':'standard'}))
    if a.command=='fetch':
        if not all((a.manifest,a.output,a.identity)):p.error('fetch requires manifest, output and identity')
        print(json.dumps(fetch(client,bucket,a.manifest,a.output,a.identity)));return
    connection=psycopg2.connect(os.environ['PG_BACKUP_DSN'])
    try:
        connection.autocommit=True
        with connection.cursor() as cursor:
            cursor.execute('SELECT NOT pg_is_in_recovery(),pg_try_advisory_lock(781923649)');primary,locked=cursor.fetchone()
        if not primary or not locked:return
        now=datetime.datetime.now(UTC);items=candidates(client,bucket)
        result={'status':'not_due','expired_objects_deleted':prune(client,bucket,items,now),'checked_at':now.isoformat()}
        if due(items,now):
            key=backup(client,bucket,connection,now,os.environ['AGE_RECIPIENT']);result.update(status='backed_up',manifest_key=key)
        print(json.dumps(result,sort_keys=True))
    finally:connection.close()
if __name__=='__main__':
    os.umask(0o077)
    main()
