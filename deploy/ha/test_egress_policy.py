import importlib.util,pathlib,unittest
spec=importlib.util.spec_from_file_location('egress_policy',pathlib.Path(__file__).with_name('egress-policy.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class EgressPolicyTest(unittest.TestCase):
    peers=['10.122.0.2','10.122.0.5','10.122.0.6']
    def test_allowances_precede_default_deny_and_preserve_ingress(self):
        steps=m.commands(self.peers)
        self.assertEqual(['ufw','default','deny','outgoing'],steps[-1])
        self.assertTrue(all('out' in step for step in steps[:-1]));self.assertFalse(any('delete' in step or 'reset' in step for step in steps))
    def test_public_ports_are_bounded_and_cluster_connections_stay_private(self):
        steps=m.commands(self.peers)
        public=[step for step in steps if 'any' in step]
        self.assertEqual({'53','67','80','123','443'},{step[-1] for step in public})
        for peer in self.peers:
            for port in m.PEER_TCP:self.assertIn(['ufw','allow','out','on','eth1','proto','tcp','to',peer,'port',port],steps)
        self.assertFalse(any('any' in step and step[-1]=='22' for step in steps))
    def test_rejects_unknown_peers_or_ports(self):
        for peers in (self.peers[:2],['8.8.8.8',*self.peers[:2]],[self.peers[0]]*3):
            with self.assertRaises(ValueError):m.commands(peers)
        for ports in ([0],[65536],[True]):
            with self.assertRaises(ValueError):m.commands(self.peers,webhook_ports=ports)
    def test_explicit_approved_custom_https_port_is_preserved(self):
        steps=m.commands(self.peers,webhook_ports=[443,8443])
        self.assertIn(['ufw','allow','out','proto','tcp','to','any','port','8443'],steps)
