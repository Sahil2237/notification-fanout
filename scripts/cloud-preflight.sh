#!/usr/bin/env bash
set -euo pipefail
command -v doctl >/dev/null
command -v gh >/dev/null
gh auth status
doctl account get --format Status --no-header
# Read-only discovery; verify region/plan choices and budget before provisioning.
doctl vpcs list --format ID,Name,Region
doctl databases list --format ID,Name,Engine,Version,Region,NumNodes
doctl registry get
