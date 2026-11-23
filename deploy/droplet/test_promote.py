import hashlib, pathlib, subprocess, tempfile, unittest
import promote

class SignedBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root=pathlib.Path(self.directory.name)
        self.incoming=self.root/'upload';self.incoming.mkdir()
        self.private=self.root/'private.pem';self.public=self.root/'public.pem'
        subprocess.run(['openssl','genpkey','-algorithm','ED25519','-out',str(self.private)],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(['openssl','pkey','-in',str(self.private),'-pubout','-out',str(self.public)],check=True,stdout=subprocess.DEVNULL)
        self.sha='a'*40
        for name in promote.FILES:(self.incoming/name).write_text('test file '+name)
        (self.incoming/'release-sha').write_text(self.sha+'\n')
        (self.incoming/'image-id').write_text('sha256:'+'b'*64+'\n')
        self.sign()
    def sign(self):
        lines=[hashlib.sha256((self.incoming/name).read_bytes()).hexdigest()+'  '+name for name in promote.FILES]
        (self.incoming/'SHA256SUMS').write_text('\n'.join(lines)+'\n')
        subprocess.run(['openssl','pkeyutl','-sign','-inkey',str(self.private),'-rawin','-in',str(self.incoming/'SHA256SUMS'),'-out',str(self.incoming/'SHA256SUMS.sig')],check=True)
    def stage(self,sha=None):return promote.stage_bundle(self.root/'releases',self.incoming,sha or self.sha,self.public)
    def test_valid_bundle_is_staged_and_can_be_retried(self):
        release=self.stage();self.assertEqual(self.sha,release.name)
        self.assertEqual(release,self.stage())
    def test_tampered_image_is_rejected(self):
        (self.incoming/'image.tar.gz').write_text('changed')
        with self.assertRaises(ValueError):self.stage()
    def test_untrusted_signature_is_rejected(self):
        (self.incoming/'SHA256SUMS.sig').write_bytes(b'bad')
        with self.assertRaises(subprocess.CalledProcessError):self.stage()
    def test_symlink_cannot_read_outside_upload(self):
        (self.incoming/'image.tar.gz').unlink()
        (self.incoming/'image.tar.gz').symlink_to(self.private)
        with self.assertRaises(OSError):self.stage()
    def test_revision_mismatch_is_rejected(self):
        with self.assertRaises(ValueError):self.stage('c'*40)
        with self.assertRaises(ValueError):self.stage('../escape')
    def test_existing_revision_cannot_be_replaced(self):
        self.stage();(self.incoming/'image.tar.gz').write_text('different');self.sign()
        with self.assertRaises(ValueError):self.stage()
    def test_signed_manifest_still_cannot_read_arbitrary_paths(self):
        path=self.incoming/'SHA256SUMS';path.write_text(path.read_text()+'0'*64+'  /etc/passwd\n')
        subprocess.run(['openssl','pkeyutl','-sign','-inkey',str(self.private),'-rawin','-in',str(path),'-out',str(self.incoming/'SHA256SUMS.sig')],check=True)
        with self.assertRaises(ValueError):self.stage()
