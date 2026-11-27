import json,pathlib,unittest
class FirewallPolicyTest(unittest.TestCase):
    def policy(self):return json.loads(pathlib.Path(__file__).with_name('firewall-policy.json').read_text())
    def test_web_and_health_sources_are_only_load_balancer_identity(self):
        p=self.policy()
        for port in ('80','443','9080'):
            rules=[r for r in p['inbound_rules'] if r['ports']==port]
            self.assertEqual(1,len(rules));self.assertEqual({'load_balancer_uids':['15a568f0-1172-4b6f-9ddb-6a4fad34ee1f']},rules[0]['sources'])
    def test_cluster_ingress_and_all_tcp_egress_are_exact_private_peers(self):
        p=self.policy();peers={'10.122.0.2/32','10.122.0.5/32','10.122.0.6/32'}
        for rule in p['inbound_rules']:
            if rule['ports'] not in ('22','80','443','9080'):self.assertEqual(peers,set(rule['sources']['addresses']))
        for rule in p['outbound_rules']:
            if rule['protocol']=='tcp' and rule['ports']=='0':self.assertEqual(peers,set(rule['destinations']['addresses']))
    def test_public_egress_has_only_reviewed_service_ports(self):
        actual={(r['protocol'],r['ports']) for r in self.policy()['outbound_rules'] if '0.0.0.0/0' in r['destinations'].get('addresses',[])}
        self.assertEqual({('icmp','0'),('tcp','53'),('tcp','80'),('tcp','443'),('udp','53'),('udp','67'),('udp','123')},actual)
    def test_explicit_ssh_user_decision_and_three_host_membership(self):
        p=self.policy();self.assertEqual({622664599,622797519,622797520},set(p['droplet_ids']))
        ssh=[r for r in p['inbound_rules'] if r['ports']=='22'];self.assertEqual(1,len(ssh));self.assertEqual(['0.0.0.0/0','::/0'],ssh[0]['sources']['addresses'])
