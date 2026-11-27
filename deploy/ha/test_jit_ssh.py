import importlib.util,pathlib,tempfile,unittest,json,os
spec=importlib.util.spec_from_file_location('jit_ssh',pathlib.Path(__file__).with_name('jit-ssh.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class FakeApi:
    def __init__(self,rules=()):self.rules=list(rules);self.calls=[];self.fail_after_post=False
    def firewall(self):return {'inbound_rules':self.rules}
    def call(self,method,suffix,payload):
        self.calls.append((method,payload))
        if method=='POST':
            self.rules.extend(payload['inbound_rules'])
            if self.fail_after_post:raise RuntimeError('uncertain acknowledgement')
        if method=='DELETE':self.rules=[r for r in self.rules if not m.exact(r,payload['inbound_rules'][0]['sources']['addresses'][0])]
class JitSshTest(unittest.TestCase):
    def test_open_and_close_only_runner_rule_and_state_has_no_token(self):
        with tempfile.TemporaryDirectory() as root:
            path=pathlib.Path(root)/'window.json';api=FakeApi([{'protocol':'tcp','ports':'443','sources':{'load_balancer_uids':['lb']}}]);m.open_window(api,'8.8.4.4',path)
            self.assertEqual(0o600,path.stat().st_mode&0o777);self.assertNotIn('token',path.read_text());m.close_window(api,path)
            self.assertEqual(1,len(api.rules));self.assertEqual('443',api.rules[0]['ports'])
    def test_preexisting_operator_exact_rule_is_never_deleted(self):
        with tempfile.TemporaryDirectory() as root:
            path=pathlib.Path(root)/'window.json';api=FakeApi([m.rule('8.8.4.4/32')]);state=m.open_window(api,'8.8.4.4',path);self.assertFalse(state['owned']);m.close_window(api,path);self.assertEqual([],api.calls)
    def test_uncertain_post_is_still_cleanup_owned(self):
        with tempfile.TemporaryDirectory() as root:
            path=pathlib.Path(root)/'window.json';api=FakeApi();api.fail_after_post=True
            with self.assertRaises(RuntimeError):m.open_window(api,'8.8.4.4',path)
            self.assertTrue(path.exists());m.close_window(api,path);self.assertEqual([],api.rules)
    def test_public_only_and_no_broad_rule_ownership(self):
        for value in ('127.0.0.1','10.122.0.2','169.254.169.254','::1','8.8.4.4/24'):
            with self.assertRaises(ValueError):m.cidr(value)
        self.assertFalse(m.exact({'protocol':'tcp','ports':'22','sources':{'addresses':['0.0.0.0/0']}},'8.8.4.4/32'))
        self.assertFalse(m.exact({'protocol':'tcp','ports':'22','sources':{'addresses':['8.8.4.4/32'],'tags':['other']}},'8.8.4.4/32'))
    def test_invalid_state_cannot_delete_other_firewall_or_network(self):
        with tempfile.TemporaryDirectory() as root:
            path=pathlib.Path(root)/'window.json';path.write_text(json.dumps({'firewall_id':'other','address':'8.8.4.4/32','owned':True}))
            with self.assertRaises(ValueError):m.close_window(FakeApi(),path)
    def test_state_is_never_overwritten_and_close_without_state_is_safe(self):
        with tempfile.TemporaryDirectory() as root:
            path=pathlib.Path(root)/'window.json';self.assertEqual('no-window-state',m.close_window(FakeApi(),path)['cleanup']);api=FakeApi();m.open_window(api,'8.8.4.4',path)
            with self.assertRaises(FileExistsError):m.open_window(api,'1.1.1.1',path)
