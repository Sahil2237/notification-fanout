#!/usr/bin/env python3
"""Publish signed, already-tested images without rebuilding or changing their contents."""
import json,os,pathlib,re,subprocess
sha=os.environ['RELEASE_SHA'];owner=os.environ['REGISTRY_OWNER'].lower()
if not re.fullmatch(r'[0-9a-f]{40}',sha) or not re.fullmatch(r'[a-z0-9-]+',owner):raise SystemExit('Invalid registry identity')
def run(*args):subprocess.run(args,check=True)
root=pathlib.Path('ha-runtime');images=json.loads((root/'images.json').read_text());published={}
entries=[('notification-fanout',pathlib.Path('release/image.tar.gz'),pathlib.Path('release/image-id').read_text().strip(),'notification-fanout:'+sha)]
for name,info in images.items():
    entries.append(('notification-'+({'tools':'ha-tools','proxy':'ha-proxy'}.get(name,name)),root/(name+'.tar.gz'),info['config_digest'],info['reference']))
for package,archive,digest,reference in entries:
    run('docker','load','-i',str(archive))
    run('python3','deploy/droplet/verify-image.py',str(archive),digest,reference)
    target='ghcr.io/'+owner+'/'+package+':'+sha
    run('docker','tag',reference,target);run('docker','push',target)
    info=json.loads(subprocess.check_output(['docker','image','inspect',target],text=True))[0]
    published[package]={'tag':target,'tested_config_digest':digest,'registry_digests':info.get('RepoDigests',[])}
pathlib.Path('published-images.json').write_text(json.dumps(published,indent=2)+'\n')
