#!/usr/bin/env python3
"""Freeze infrastructure references to the image digests actually exercised on this CI runner."""
import json,pathlib,subprocess,sys
path=pathlib.Path(sys.argv[1]);text=path.read_text()
for image in ('postgres:17','apache/kafka:4.3.1','caddy:2.11.9-alpine'):
    metadata=json.loads(subprocess.check_output(['docker','image','inspect',image],text=True))[0]
    digests=metadata.get('RepoDigests',[])
    if not digests:raise SystemExit('Missing tested image digest: '+image)
    digest=digests[0]
    if '@sha256:' not in digest:raise SystemExit('Invalid infrastructure digest')
    needle='image: '+image+'\n'
    if needle not in text:raise SystemExit('Expected image reference missing: '+image)
    text=text.replace(needle,'image: '+digest+'\n')
path.write_text(text)
