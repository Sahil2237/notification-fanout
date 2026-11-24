#!/usr/bin/env bash
# Read-only host integration check; run again after a real reboot/fencing drill.
set -euo pipefail
[[ $(id -u) == 0 ]] || exit 1
systemctl is-enabled notification-watchdog.service >/dev/null
systemctl is-active notification-watchdog.service >/dev/null
[[ $(systemctl show docker.service -p Requires --value) == *notification-watchdog.service* ]]
[[ $(systemctl show docker.service -p After --value) == *notification-watchdog.service* ]]
[[ -c /dev/watchdog ]]
[[ $(stat -c '%u:%g:%a' /dev/watchdog) == 999:999:660 ]]
[[ $(cat /sys/class/watchdog/watchdog0/identity) == 'Software Watchdog' ]]
printf 'Required watchdog boot dependency and device permissions verified\n'
