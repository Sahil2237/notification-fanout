#!/usr/bin/env python3
"""Local-only real PostgreSQL/age/pg_restore round trip with a filesystem S3 adapter."""
import datetime,importlib.util,io,json,os,pathlib,shutil,subprocess,tempfile,uuid
if os.environ.get('NOTIFICATION_HA_LOCAL_TEST')!='true' or not all(x.startswith('172.29.0.') for x in os.environ.get('PGHOST','').split(',')):
    raise SystemExit('Disposable Rancher database only')
import psycopg2
spec=importlib.util.spec_from_file_location('backup','/opt/ha/spaces-backup.py');backup=importlib.util.module_from_spec(spec);spec.loader.exec_module(backup)
os.umask(0o077);os.environ['BACKUP_MANIFEST_KEY']='local-only-manifest-key-'+uuid.uuid4().hex
class FilesystemS3:
    def __init__(self,path):self.root=path;self.metadata={}
    def upload_file(self,source,bucket,key,ExtraArgs):
        assert ExtraArgs['ACL']=='private';target=self.root/key;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target);self.metadata[key]=ExtraArgs['Metadata']
    def head_object(self,Bucket,Key):return {'ContentLength':(self.root/Key).stat().st_size,'Metadata':self.metadata[Key]}
    def put_object(self,Bucket,Key,Body,ACL,ContentType):
        assert ACL=='private';(self.root/Key).write_bytes(Body)
    def get_object(self,Bucket,Key):return {'Body':io.BytesIO((self.root/Key).read_bytes())}
    def download_file(self,bucket,key,path):shutil.copyfile(self.root/key,path)
with tempfile.TemporaryDirectory(prefix='local-backup-drill-') as directory:
    work=pathlib.Path(directory);identity=work/'identity';subprocess.run(['age-keygen','-o',str(identity)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    recipient=subprocess.check_output(['age-keygen','-y',str(identity)],text=True).strip();client=FilesystemS3(work/'objects')
    connection=psycopg2.connect(os.environ['PG_BACKUP_DSN'])
    try:
        manifest_key=backup.backup(client,'local-adapter',connection,datetime.datetime.now(datetime.timezone.utc),recipient)
        with connection.cursor() as query:
            query.execute('SHOW transaction_isolation');assert query.fetchone()[0]=='repeatable read'
            query.execute('SHOW transaction_read_only');assert query.fetchone()[0]=='on'
    finally:connection.close()
    manifest=json.loads(client.get_object(Bucket='local-adapter',Key=manifest_key)['Body'].read());backup.verify_manifest(manifest,os.environ['BACKUP_MANIFEST_KEY'])
    backup.fetch(client,'local-adapter',manifest_key,str(work/'restore'),str(identity))
    snapshot=json.loads((work/'restore/snapshot.json').read_text());database='local_restore_'+uuid.uuid4().hex[:12]
    owner=psycopg2.connect(os.environ['PG_ADMIN_DSN']);owner.autocommit=True
    try:
        with owner.cursor() as query:query.execute('CREATE DATABASE '+database)
        env=dict(os.environ);env['PGUSER']='notification_owner';env['PGPASSWORD']=os.environ['PG_ADMIN_PASSWORD'];env['PGDATABASE']=database
        subprocess.run(['pg_restore','--dbname='+database,'--exit-on-error','--no-owner','--no-acl',str(work/'restore/database.dump')],env=env,check=True,stdout=subprocess.DEVNULL)
        restored=psycopg2.connect(os.environ['PG_ADMIN_DSN'].replace('dbname=notifications','dbname='+database))
        try:
            with restored.cursor() as query:
                for table,expected in snapshot['row_counts'].items():
                    assert table in backup.TABLES;query.execute('SELECT count(*) FROM '+table);assert query.fetchone()[0]==expected,table
        finally:restored.close()
        print(json.dumps({'real_postgresql_age_restore':True,'repeatable_read_snapshot':True,'manifest_authenticated':True,'all_table_counts_match':True,'row_counts':snapshot['row_counts'],'storage_scope':'local filesystem S3 adapter; separate live Spaces round trip required'}))
    finally:
        with owner.cursor() as query:query.execute('DROP DATABASE IF EXISTS '+database)
        owner.close()
