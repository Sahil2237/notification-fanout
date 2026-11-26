import copy,importlib.util,json,pathlib,unittest
ROOT=pathlib.Path(__file__).parent
spec=importlib.util.spec_from_file_location('capacity',ROOT/'capacity.py');capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(capacity)
class CapacityProfileTest(unittest.TestCase):
    def profile(self):return json.loads((ROOT/'capacity-1000-rps.json').read_text())
    def test_profile_covers_five_minutes_and_two_identity_aliases(self):
        p=capacity.validate(self.profile());self.assertGreaterEqual(p['application']['CACHE_MAX_ENTRIES'],1000*300*2)
    def test_refuses_insufficient_host_memory(self):
        p=self.profile()
        with self.assertRaises(ValueError):capacity.memory_guard(p,1024*capacity.MIB,8*capacity.MIB)
        self.assertGreater(capacity.memory_guard(p,2300*capacity.MIB,8*capacity.MIB),384*capacity.MIB)
    def test_refuses_cache_count_or_byte_underprovisioning(self):
        for key,value in [('CACHE_MAX_ENTRIES',10000),('CACHE_MAX_BYTES',33554432)]:
            p=self.profile();p['application'][key]=value
            with self.assertRaises(ValueError):capacity.validate(p)
    def test_refuses_unbounded_resources_and_unrelated_overrides(self):
        for section,key,value in [('redis','container_memory_mib',4096),('redis','cpus',4),('application','DB_PASSWORD','bad')]:
            p=self.profile();p[section][key]=value
            with self.assertRaises(ValueError):capacity.validate(p)
    def test_changes_only_app_settings_and_preserves_other_services_and_secrets(self):
        inventory={'application':{'DB_WRITE_POOL_MAX':8},'redis':{'enabled':True},'nodes':['unchanged']}
        compose={'services':{'app':{'environment':{'DB_PASSWORD':'private','WORKER_THREADS':'4'}},'migrate':{'environment':{'DB_PASSWORD':'owner-private'}},'postgres':{'image':'pinned-pg'},'kafka':{'image':'pinned-kafka'}}}
        old=copy.deepcopy(compose);inv,result=capacity.application_configuration(inventory,compose,self.profile())
        self.assertEqual(old,compose);self.assertEqual(old['services']['postgres'],result['services']['postgres']);self.assertEqual(old['services']['kafka'],result['services']['kafka'])
        self.assertEqual('private',result['services']['app']['environment']['DB_PASSWORD']);self.assertEqual('owner-private',result['services']['migrate']['environment']['DB_PASSWORD'])
        self.assertEqual('66000',result['services']['app']['environment']['REQUESTS_PER_MINUTE']);self.assertEqual(8,inv['application']['DB_WRITE_POOL_MAX']);self.assertTrue(inv['redis']['enabled'])
