import copy,hashlib,importlib.util,json,pathlib,unittest
spec=importlib.util.spec_from_file_location('image_identity',pathlib.Path(__file__).with_name('verify-image.py'))
image=importlib.util.module_from_spec(spec);spec.loader.exec_module(image)
class ImageIdentityTest(unittest.TestCase):
    def setUp(self):
        self.config={'architecture':'amd64','os':'linux','rootfs':{'diff_ids':['sha256:layer']},'config':{'User':'10001','Entrypoint':['java','-jar','app.jar'],'Env':['JAVA_HOME=/opt/java'],'WorkingDir':'/opt/app','Labels':{'revision':'test'}}}
        self.raw=json.dumps(self.config).encode();self.digest='sha256:'+hashlib.sha256(self.raw).hexdigest()
        self.inspected={'Id':'sha256:a-different-OCI-manifest-id','Architecture':'amd64','Os':'linux','RootFS':{'Layers':['sha256:layer']},'Config':copy.deepcopy(self.config['config'])|{'Cmd':None,'AttachStdin':False}}
    def test_same_filesystem_and_config_are_accepted_across_engine_ids(self):
        result=image.verify(self.raw,self.digest,self.inspected)
        self.assertEqual(self.digest,result['verified_config_digest'])
    def test_changed_layer_is_rejected(self):
        self.inspected['RootFS']['Layers']=['sha256:different']
        with self.assertRaises(ValueError):image.verify(self.raw,self.digest,self.inspected)
    def test_changed_entrypoint_is_rejected(self):
        self.inspected['Config']['Entrypoint']=['sh']
        with self.assertRaises(ValueError):image.verify(self.raw,self.digest,self.inspected)
    def test_changed_user_is_rejected(self):
        self.inspected['Config']['User']='root'
        with self.assertRaises(ValueError):image.verify(self.raw,self.digest,self.inspected)
    def test_changed_archive_config_digest_is_rejected(self):
        with self.assertRaises(ValueError):image.verify(self.raw,'sha256:wrong',self.inspected)
