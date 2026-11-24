import importlib.util,pathlib,tempfile,unittest,os
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('rate_cluster',pathlib.Path(__file__).with_name('redis-cluster.py'));rate=importlib.util.module_from_spec(spec);spec.loader.exec_module(rate)
def inventory():return {'nodes':[{'id':n,'private_ip':'10.122.0.'+str(n)} for n in (1,2,3)]}
class RedisConfigurationTest(unittest.TestCase):
    def test_tls_only_and_no_public_listener(self):
        compose,files=rate.render(inventory(),rate.credentials(),1,'redis:tested')
        for name in ('redis','sentinel'):
            service=compose['services'][name];self.assertNotIn('ports',service);self.assertTrue(service['read_only']);self.assertEqual(['ALL'],service['cap_drop'])
        self.assertIn('port 0',files['redis/redis.conf']);self.assertIn('tls-port 6379',files['redis/redis.conf']);self.assertIn('tls-port 26379',files['sentinel/sentinel.conf'])
        self.assertIn('min-replicas-to-write 1',files['redis/redis.conf']);self.assertIn('appendfsync always',files['redis/redis.conf'])
    def test_replication_and_sentinel_quorum(self):
        _,files=rate.render(inventory(),rate.credentials(),2,'redis:tested')
        self.assertIn('replicaof 10.122.0.1 6379',files['redis/redis.conf'])
        self.assertIn('sentinel monitor notification-rate 10.122.0.1 6379 2',files['sentinel/sentinel.conf'])
    def test_no_default_user_or_admin_permission_for_application(self):
        _,files=rate.render(inventory(),rate.credentials(),1,'redis:tested')
        self.assertIn('user default off',files['redis/users.acl']);limiter=next(line for line in files['redis/users.acl'] if line.startswith('user limiter '))
        self.assertIn('~notification:limits:v1:*',limiter);self.assertNotIn('+@all',limiter);self.assertNotIn('+config',limiter)
        discovery=next(line for line in files['sentinel/users.acl'] if line.startswith('user discovery '));self.assertNotIn('+@all',discovery);self.assertNotIn('+sentinel|failover',discovery)
    def test_rejects_invalid_topology_and_shared_or_weak_credentials(self):
        value=inventory();value['nodes'][0]['private_ip']='8.8.8.8'
        with self.assertRaises(ValueError):rate.nodes_of(value)
        secret=rate.credentials();secret['limiter']=secret['admin']
        with self.assertRaises(ValueError):rate.credentials(secret)
        with self.assertRaises(ValueError):rate.credentials({})
        secret=rate.credentials();secret['limiter']='weak'
        with self.assertRaises(ValueError):rate.credentials(secret)
    def test_restart_bootstrap_cannot_reset_sentinel_configuration(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(rate.os,'chown'):
            secret=rate.credentials();rate.bootstrap(inventory(),secret,1,'redis:tested',tmp)
            with self.assertRaises(ValueError):rate.bootstrap(inventory(),secret,1,'redis:tested',tmp)
            self.assertEqual(0o600,(pathlib.Path(tmp)/'redis/redis.conf').stat().st_mode&0o777)
if __name__=='__main__':unittest.main()
