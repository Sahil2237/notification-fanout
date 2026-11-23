#!/usr/bin/env bash
set -euo pipefail
# Interactive authentication only. Never paste credentials into chat or commit them.
gh auth login --hostname github.com --git-protocol https --web
doctl auth init --context notification-assessment
