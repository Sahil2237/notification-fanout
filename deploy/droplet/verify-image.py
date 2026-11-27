#!/usr/bin/env python3
"""Compare portable image contents, not engine-specific OCI manifest versus config IDs."""
import hashlib,json,subprocess,sys,tarfile

def normalized(config):
    # Legacy builders store the parent image reference in Config.Image. Docker28.2+
    # removes this non-standard build metadata from inspect; it is not runtime config.
    # Archive SHA-256, every filesystem layer and all execution settings remain checked.
    # https://docs.docker.com/engine/deprecated/#non-standard-fields-in-image-inspect
    return {key:value for key,value in config.items() if key != "Image" and value not in (None,False,0,"",[],{})}

def identity(inspected):
    content={'architecture':inspected['Architecture'],'os':inspected['Os'],
             'layers':inspected['RootFS']['Layers'],'config':normalized(inspected['Config'])}
    return hashlib.sha256(json.dumps(content,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def verify(config_bytes,expected_digest,inspected):
    actual='sha256:'+hashlib.sha256(config_bytes).hexdigest()
    if actual!=expected_digest:raise ValueError('Signed archive config digest mismatch')
    config=json.loads(config_bytes)
    expected={'Architecture':config['architecture'],'Os':config['os'],
              'RootFS':{'Layers':config['rootfs']['diff_ids']},'Config':config['config']}
    if identity(expected)!=identity(inspected):raise ValueError('Loaded filesystem or execution configuration differs from tested image')
    return {'verified_config_digest':actual,'portable_identity':identity(inspected),'loaded_engine_id':inspected.get('Id')}

def main():
    if len(sys.argv)==4 and sys.argv[1]=='--record':
        record=json.load(open(sys.argv[2]))
        inspected=json.loads(subprocess.check_output(['docker','image','inspect',sys.argv[3]],text=True))[0]
        if identity(inspected)!=record['portable_identity']:raise SystemExit('Running image no longer matches verified contents')
        print(json.dumps(record));return
    if len(sys.argv)!=4:raise SystemExit('Usage: verify-image.py ARCHIVE EXPECTED_CONFIG_DIGEST IMAGE_TAG')
    archive,expected,tag=sys.argv[1:]
    with tarfile.open(archive) as tar:
        manifests=json.load(tar.extractfile('manifest.json'))
        if len(manifests)!=1 or tag not in manifests[0].get('RepoTags',[]):raise ValueError('Unexpected image tags in signed archive')
        member=tar.getmember(manifests[0]['Config'])
        if not member.isfile() or member.size>1024*1024:raise ValueError('Invalid image configuration')
        config=tar.extractfile(member).read()
    inspected=json.loads(subprocess.check_output(['docker','image','inspect',tag],text=True))[0]
    print(json.dumps(verify(config,expected,inspected)))

if __name__=='__main__':main()
