import datetime,importlib.util,json,pathlib,unittest
from unittest.mock import MagicMock,patch
ROOT=pathlib.Path(__file__).parent

def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/file);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
render=load('ha_render','render.py');backup=load('spaces_backup','spaces-backup.py');identity=load('ha_identity','prepare-secrets.py');UTC=datetime.timezone.utc

def inventory():return {'nodes':[{'id':n,'private_ip':'10.122.0.'+str(n)} for n in (1,2,3)],'tls_domain':'203.0.113.8','app_image':'app:tested','tools_image':'tools:tested','caddy_image':'proxy:tested','etcd_image':'etcd:v3.6.8','kafka_image':'apache/kafka:4.3.2'}
def secrets():return {key:'a'*32 for key in ('pg_owner_password','replication_password','db_password','admin_token','api_token','signing_secret','caddy_password','backup_password','kafka_cluster_id','kafka_tls_password')}|{'webhook_origins':['https://webhook.example']}
class ClusterConfigurationTest(unittest.TestCase):
    def test_requires_exactly_three_distinct_nodes(self):
        for nodes in (inventory()['nodes'][:2],inventory()['nodes']+[{'id':4,'private_ip':'10.0.0.4'}]):
            value=inventory();value['nodes']=nodes
            with self.assertRaises(ValueError):render.validate(value)
    def test_rejects_duplicate_and_public_addresses(self):
        for ip in ('8.8.8.8','127.0.0.1','10.122.0.1'):
            value=inventory();value['nodes'][1]['private_ip']=ip
            with self.assertRaises(ValueError):render.validate(value)
    def test_rejects_injected_image_or_weak_secret(self):
        value=inventory();value['tools_image']='image; touch /tmp/bad'
        with self.assertRaises(ValueError):render.validate(value)
        secret=secrets();secret['replication_password']='weak'
        with self.assertRaises(ValueError):render.render(inventory(),secret,1)
    def test_postgresql_never_disables_synchronous_durability(self):
        compose,pg=render.render(inventory(),secrets(),1);dcs=pg['bootstrap']['dcs']
        self.assertTrue(dcs['synchronous_mode']);self.assertTrue(dcs['synchronous_mode_strict']);self.assertEqual(1,dcs['synchronous_node_count']);self.assertEqual(0,dcs['maximum_lag_on_failover'])
        self.assertEqual('remote_apply',dcs['postgresql']['parameters']['synchronous_commit']);self.assertEqual('on',dcs['postgresql']['parameters']['fsync']);self.assertEqual('required',pg['watchdog']['mode'])
    def test_kafka_has_three_copies_and_no_unclean_election(self):
        compose,_=render.render(inventory(),secrets(),2);env=compose['services']['kafka']['environment']
        self.assertEqual('3',env['KAFKA_DEFAULT_REPLICATION_FACTOR']);self.assertEqual('2',env['KAFKA_MIN_INSYNC_REPLICAS']);self.assertEqual('false',env['KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE']);self.assertEqual(3,len(env['KAFKA_CONTROLLER_QUORUM_VOTERS'].split(',')))
        self.assertEqual('required',env['KAFKA_SSL_CLIENT_AUTH']);self.assertEqual('false',env['KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND'])
    def test_app_cannot_read_control_plane_credentials(self):
        compose,pg=render.render(inventory(),secrets(),3)
        self.assertFalse(any('/control' in volume for volume in compose['services']['app']['volumes']))
        self.assertEqual('https',pg['etcd3']['protocol']);self.assertEqual(3,len(pg['etcd3']['hosts'].split(',')))
        command=compose['services']['etcd']['command'];self.assertIn('--client-cert-auth=true',command);self.assertIn('--peer-client-cert-auth=true',command)
class ApplicationBudgetTest(unittest.TestCase):
    def test_per_second_quota_and_optional_tenant_credentials(self):
        secret=secrets();secret['tenant_credentials']={'service':secret['api_token'],'tenant-2':'b'*32}
        compose,_=render.render(inventory(),secret,1);env=compose['services']['app']['environment']
        self.assertEqual('1000',env['REQUESTS_PER_SECOND']);self.assertEqual(secret['tenant_credentials'],json.loads(env['TENANT_CREDENTIALS']))
        self.assertEqual(env['TENANT_CREDENTIALS'],compose['services']['migrate']['environment']['TENANT_CREDENTIALS'])
        for invalid in ({'tenant-2':'b'*32},{'service':secret['api_token'],'other':secret['api_token']},{'service':secret['api_token'],'__admin':'b'*32}):
            secret['tenant_credentials']=invalid
            with self.assertRaises(ValueError):render.render(inventory(),secret,1)
    def test_defaults_and_bounded_pool_overrides_are_rendered(self):
        compose,_=render.render(inventory(),secrets(),1)
        env=compose['services']['app']['environment']
        self.assertEqual('8',env['DB_WRITE_POOL_MAX']);self.assertEqual('4',env['DB_READ_POOL_MAX'])
        self.assertEqual('1',env['SUBSCRIBER_CONCURRENCY']);self.assertIn('--name=node1',compose['services']['etcd']['command'])
        custom=inventory();custom['application']={'DB_WRITE_POOL_MAX':12,'DB_READ_POOL_MAX':6,'REQUESTS_PER_MINUTE':60}
        compose,_=render.render(custom,secrets(),1)
        self.assertEqual('12',compose['services']['app']['environment']['DB_WRITE_POOL_MAX'])
    def test_invalid_budget_is_rejected_before_deployment(self):
        for value in (0,17,True,'8'):
            custom=inventory();custom['application']={'DB_WRITE_POOL_MAX':value}
            with self.assertRaises(ValueError):render.render(custom,secrets(),1)

class MetricsConfigurationTest(unittest.TestCase):
    def test_disabled_by_default_and_no_ingest_secret_in_migration(self):
        compose,_=render.render(inventory(),secrets(),1)
        self.assertNotIn('env_file',compose['services']['app'])
        self.assertEqual('false',compose['services']['migrate']['environment']['METRICS_ENABLED'])
    def test_metrics_use_separate_protected_file_and_per_droplet_identity(self):
        value=inventory();value['nodes'][1]['droplet_id']=622797519
        value['telemetry']={'enabled':True,'team_id':'01234567-0123-4123-8123-0123456789ab'}
        compose,_=render.render(value,secrets(),2)
        app=compose['services']['app'];env=app['environment']
        self.assertEqual(['./metrics.env'],app['env_file'])
        self.assertEqual('622797519',env['DO_DROPLET_ID'])
        self.assertEqual('true',env['METRICS_ENABLED'])
        self.assertNotIn('DO_METRICS_INGEST_TOKEN',env)
        self.assertNotIn('env_file',compose['services']['migrate'])
        self.assertEqual('false',compose['services']['migrate']['environment']['METRICS_ENABLED'])
    def test_rejects_missing_resource_attribution(self):
        value=inventory();value['telemetry']={'enabled':True,'team_id':'not-a-team-uuid'}
        with self.assertRaises(ValueError):render.render(value,secrets(),1)

class RedisApplicationConfigurationTest(unittest.TestCase):
    def test_app_receives_only_limiter_and_discovery_credentials(self):
        value=inventory();value['redis']={'enabled':True};secret=secrets()
        secret.update(redis_limiter='l'*64,redis_discovery='d'*64)
        compose,_=render.render(value,secret,1);env=compose['services']['app']['environment']
        self.assertEqual('l'*64,env['REDIS_PASSWORD']);self.assertEqual('d'*64,env['REDIS_SENTINEL_PASSWORD'])
        self.assertEqual(3,len(json.loads(env['REDIS_SENTINELS'])))
        self.assertFalse(any('ADMIN' in key and 'REDIS' in key for key in env))
    def test_missing_redis_credentials_block_rendering(self):
        value=inventory();value['redis']={'enabled':True}
        with self.assertRaises(ValueError):render.render(value,secrets(),1)

class IdentityMigrationTest(unittest.TestCase):
    def test_preserves_api_signing_and_database_identity(self):
        old={key:key.replace('_','')+'a'*64 for key in ('PG_OWNER_PASSWORD','DB_PASSWORD','ADMIN_TOKEN','API_TOKEN','WEBHOOK_SIGNING_SECRET')};old['WEBHOOK_ORIGINS']='["https://example.com"]'
        value=identity.build(old,'k'*64)
        self.assertEqual(old['API_TOKEN'],value['api_token']);self.assertEqual(old['WEBHOOK_SIGNING_SECRET'],value['signing_secret']);self.assertEqual(old['DB_PASSWORD'],value['db_password'])
        self.assertNotEqual(value['backup_password'],value['replication_password'])
    def test_missing_existing_identity_fails_closed(self):
        with self.assertRaises(KeyError):identity.build({},'k'*64)

class BackupPolicyTest(unittest.TestCase):
    def setUp(self):
        self.now=datetime.datetime(2026,11,24,3,tzinfo=UTC);self.base=backup.PREFIX+'20261124T030000Z-'+'a'*32
    def obj(self,suffix='json',age_hours=0):return {'Key':self.base+'.'+suffix,'LastModified':self.now-datetime.timedelta(hours=age_hours)}
    def test_durable_delivery_controls_are_in_snapshot_verification(self):
        self.assertIn('delivery_dead_letters',backup.TABLES);self.assertNotIn('fixed_window_limits',backup.TABLES)
        self.assertTrue({'subscription_versions','delivery_identities','delivery_history'}.issubset(backup.TABLES))
        self.assertEqual(13,len(backup.TABLES))
    def test_first_backup_and_incomplete_upload_are_due(self):
        self.assertTrue(backup.due([],self.now));self.assertTrue(backup.due([self.obj('tar.age')],self.now))
    def test_does_not_repeat_a_successful_daily_backup(self):self.assertFalse(backup.due([self.obj()],self.now))
    def test_previous_day_is_due_after_scheduled_hour(self):self.assertTrue(backup.due([self.obj(age_hours=4)],self.now))
    def test_retention_boundary_is_exactly_twenty_four_hours(self):
        young=self.obj(age_hours=23.999);old=self.obj(age_hours=24)
        self.assertEqual([old['Key']],backup.expired([young,old],self.now))
    def test_never_selects_foreign_objects_for_deletion(self):
        client=MagicMock();client.get_paginator.return_value.paginate.return_value=[{'Contents':[self.obj(age_hours=25),{'Key':'important/unrelated.json','LastModified':self.now-datetime.timedelta(days=2)}]}]
        values=backup.candidates(client,'bucket');self.assertEqual(1,len(values));backup.prune(client,'bucket',values,self.now)
        client.delete_object.assert_called_once_with(Bucket='bucket',Key=self.obj(age_hours=25)['Key'])
    def test_manifest_authentication_rejects_tampering(self):
        value={'schema_version':1,'object_key':self.base+'.tar.age','sha256':'a'*64,'bytes':10}
        signed=backup.seal_manifest(value,'secret'*8);self.assertEqual(value['object_key'],backup.verify_manifest(signed,'secret'*8)['object_key'])
        signed['bytes']=11
        with self.assertRaises(ValueError):backup.verify_manifest(signed,'secret'*8)
    def test_manifest_rejects_path_escape_and_invalid_hash(self):
        for key,sha in (('../outside.tar.age','a'*64),(self.base+'.tar.age','not-a-hash')):
            with self.assertRaises(ValueError):backup.safe_manifest({'schema_version':1,'object_key':key,'sha256':sha,'bytes':1})
    def test_failed_upload_never_publishes_completion_manifest(self):
        connection=MagicMock();cursor=connection.cursor.return_value.__enter__.return_value;cursor.fetchone.side_effect=[('snapshot',)]+[(0,)]*len(backup.TABLES)
        client=MagicMock();client.upload_file.side_effect=OSError('simulated upload failure')
        def run(args,**kwargs):
            if args[0]=='pg_dump':pathlib.Path(next(a.split('=',1)[1] for a in args if a.startswith('--file='))).write_bytes(b'dump')
            elif args[0]=='age':kwargs['stdout'].write(b'encrypted')
        with patch.object(backup.subprocess,'run',side_effect=run):
            with self.assertRaises(OSError):backup.backup(client,'bucket',connection,self.now,'public-recipient')
        client.put_object.assert_not_called()
if __name__=='__main__':unittest.main()

class CacheContractTest(unittest.TestCase):
    def test_cache_acl_is_namespaced_without_eviction_configuration_privilege(self):
        source=(pathlib.Path(__file__).parent/'redis-cluster.py').read_text()
        line=next(line for line in source.splitlines() if "'user limiter on >'" in line)
        self.assertIn('~notification:cache:v2:*',line)
        self.assertIn('~notification:limits:v1:*',line)
        self.assertNotIn('+config',line)
        self.assertNotIn('+flush',line)
        self.assertNotIn('+@all',line)
