import importlib.util,pathlib,unittest
spec=importlib.util.spec_from_file_location('network_policy',pathlib.Path(__file__).with_name('network-policy.py'));network=importlib.util.module_from_spec(spec);spec.loader.exec_module(network)
class NetworkPolicyTest(unittest.TestCase):
    def test_only_vpc_interface_and_destination_are_allowed(self):
        result=network.policy('10.122.0.2','10.122.0.0/20','eth1')
        self.assertEqual(3,len(result))
        for command,port in zip(result,(80,443,9080)):
            self.assertEqual(['ufw','allow','in','on','eth1','proto','tcp','from','10.122.0.0/20','to','10.122.0.2','port',str(port)],command)
    def test_rejects_public_broad_or_wrong_interface_inputs(self):
        for address,cidr,interface in [('8.8.8.8','8.8.8.0/24','eth1'),('10.122.0.2','0.0.0.0/0','eth1'),('10.123.0.2','10.122.0.0/20','eth1'),('10.122.0.2','10.122.0.0/20','eth1;id'),('10.122.0.2','10.122.0.0/20','lo'),('10.122.0.2','10.0.0.0/8','eth1'),('127.0.0.1','127.0.0.0/16','eth1'),('169.254.0.1','169.254.0.0/16','eth1')]:
            with self.assertRaises(ValueError):network.policy(address,cidr,interface)
    def test_removes_only_broad_web_and_old_health_rules(self):
        added='\n'.join(['ufw allow 22/tcp','ufw allow 80/tcp','ufw allow 443/tcp','ufw allow from 10.122.0.0/20 to any port 9080 proto tcp','ufw allow from 10.122.0.2 to any port 5432 proto tcp'])
        result=network.obsolete_rules(added,'10.122.0.0/20');self.assertEqual(3,len(result))
        self.assertFalse(any('22/tcp' in c or '5432' in c for c in result))
    def test_repeated_policy_has_no_broad_rules_to_remove(self):
        self.assertEqual([],network.obsolete_rules('ufw allow in on eth1 from 10.122.0.0/20 to 10.122.0.2 port 443 proto tcp','10.122.0.0/20'))
    def test_future_ha_bootstrap_does_not_reopen_public_proxy_ports(self):
        bootstrap=pathlib.Path(__file__).with_name('bootstrap.sh').read_text()
        self.assertNotIn('ufw allow 80/tcp',bootstrap);self.assertNotIn('ufw allow 443/tcp',bootstrap)
        for port in (80,443,9080):self.assertIn('ufw allow in on eth1 proto tcp from 10.122.0.0/20 to any port '+str(port),bootstrap)
