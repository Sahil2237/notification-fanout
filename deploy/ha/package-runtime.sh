#!/usr/bin/env bash
set -euo pipefail
mkdir -p .ha-runtime
cp deploy/ha/{render.py,create-pki.py,app-entrypoint.sh,Caddyfile,run-backup.sh,notification-backup.service,notification-backup.timer,spaces-backup.py,install-runtime.sh,restore-test.sh,prepare-secrets.py,initialize-databases.py} .ha-runtime/
cp deploy/droplet/verify-image.py .ha-runtime/
# Package the exact images already loaded and scanned; never refresh mutable tags here.
docker image inspect "$ETCD_IMAGE" "$KAFKA_IMAGE" "$HA_TOOLS_IMAGE" "$HA_PROXY_IMAGE" >/dev/null
python3 - <<'PYCODE'
import json,os,pathlib,subprocess
root=pathlib.Path('.ha-runtime');images={}
for name,var in [('tools','HA_TOOLS_IMAGE'),('proxy','HA_PROXY_IMAGE'),('etcd','ETCD_IMAGE'),('kafka','KAFKA_IMAGE')]:
    reference=os.environ[var]
    info=json.loads(subprocess.check_output(['docker','image','inspect',reference],text=True))[0]
    images[name]={'reference':reference,'config_digest':info['Id'],'repo_digests':info.get('RepoDigests',[])}
    with (root/(name+'.tar.gz')).open('wb') as output:
        save=subprocess.Popen(['docker','save',reference],stdout=subprocess.PIPE)
        gzip=subprocess.run(['gzip'],stdin=save.stdout,stdout=output,check=True);save.stdout.close()
        if save.wait()!=0:raise RuntimeError('Image export failed')
(root/'images.json').write_text(json.dumps(images,indent=2))
PYCODE
python3 - <<'PYCODE'
import hashlib,pathlib
root=pathlib.Path('.ha-runtime');files=sorted(p for p in root.iterdir() if p.is_file() and p.name not in ('SHA256SUMS','SHA256SUMS.sig'))
with (root/'SHA256SUMS').open('w') as out:
    for path in files:
        with path.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
        out.write(digest+'  '+path.name+'\n')
PYCODE
