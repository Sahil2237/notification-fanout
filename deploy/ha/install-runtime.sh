#!/usr/bin/env bash
# Explicit operator operation. Application CI cannot sudo this infrastructure installer.
set -euo pipefail
[[ $(id -u) == 0 ]] || exit 1
bundle=$(realpath "${1:?Signed runtime bundle}")
openssl pkeyutl -verify -pubin -inkey /etc/notification-fanout-release.pub -rawin -in "$bundle/SHA256SUMS" -sigfile "$bundle/SHA256SUMS.sig" >/dev/null
(cd "$bundle" && sha256sum --check --status SHA256SUMS)
python3 - "$bundle" <<'PYCODE'
import json,pathlib,subprocess,sys
bundle=pathlib.Path(sys.argv[1]);images=json.loads((bundle/'images.json').read_text())
for name,info in images.items():
    subprocess.run(['docker','load','-i',str(bundle/(name+'.tar.gz'))],check=True)
    subprocess.run(['python3',str(bundle/'verify-image.py'),str(bundle/(name+'.tar.gz')),info['config_digest'],info['reference']],check=True)
PYCODE
install -d -m 0700 /opt/notification-fanout/ha
install -m 0600 "$bundle/images.json" /opt/notification-fanout/ha/images.json
