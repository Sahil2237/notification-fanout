#!/usr/bin/env python3
"""Bound host egress while preserving DNS, web dependencies, DHCP/NTP and private cluster peers."""
import argparse,ipaddress,json,os,pathlib,shutil,subprocess
PUBLIC_TCP=(53,80,443)
PUBLIC_UDP=(53,67,123)
PEER_TCP=('22','2379:2380','5432','6379','8008','9092:9093','26379')

def commands(peers,interface='eth1',webhook_ports=(443,)):
    if len(peers)!=3 or len(set(peers))!=3:raise ValueError('Exactly three distinct cluster peers required')
    if interface!='eth1':raise ValueError('Reviewed private interface is eth1')
    for peer in peers:
        value=ipaddress.ip_address(peer)
        if value.version!=4 or value not in ipaddress.ip_network('10.122.0.0/20'):raise ValueError('Peer outside approved VPC')
    if len(webhook_ports)>20 or any(type(p)!=int or not 1<=p<=65535 for p in webhook_ports):raise ValueError('Invalid approved webhook ports')
    result=[]
    for port in sorted(set(PUBLIC_TCP)|set(webhook_ports)):result.append(['ufw','allow','out','proto','tcp','to','any','port',str(port)])
    for port in PUBLIC_UDP:result.append(['ufw','allow','out','proto','udp','to','any','port',str(port)])
    for peer in peers:
        for port in PEER_TCP:result.append(['ufw','allow','out','on',interface,'proto','tcp','to',peer,'port',port])
    result.append(['ufw','default','deny','outgoing'])
    return result

def run(args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)

def restore(root):
    root=pathlib.Path(root)
    for name in ('user.rules','user6.rules','defaults'):
        if not (root/name).is_file():raise ValueError('Complete snapshot required')
    for name in ('user.rules','user6.rules'):shutil.copy2(root/name,pathlib.Path('/etc/ufw')/name)
    shutil.copy2(root/'defaults','/etc/default/ufw');run(['ufw','reload'])

def apply(peers,interface,webhook_ports,root):
    steps=commands(peers,interface,webhook_ports)
    if os.geteuid()!=0 or 'Status: active' not in run(['ufw','status']):raise ValueError('Existing root-operated active UFW required')
    existing=run(['ufw','show','added'])
    if any(line.startswith('ufw allow out') for line in existing.splitlines()):raise ValueError('Existing explicit outbound rules require review before replacement')
    root=pathlib.Path(root)
    if root.exists():raise ValueError('New snapshot directory required')
    root.mkdir(parents=True,mode=0o700)
    for name in ('user.rules','user6.rules'):shutil.copy2(pathlib.Path('/etc/ufw')/name,root/name)
    shutil.copy2('/etc/default/ufw',root/'defaults');shutil.copy2(__file__,root/'egress-policy.py')
    run(['systemd-run','--unit=notification-egress-rollback','--on-active=180s','/usr/bin/python3',str(root/'egress-policy.py'),'--restore',str(root)])
    try:
        for step in steps:run(step)
        if 'DEFAULT_OUTPUT_POLICY="DROP"' not in pathlib.Path('/etc/default/ufw').read_text():raise RuntimeError('Outbound default-deny missing')
        manifest={'public_tcp':sorted(set(PUBLIC_TCP)|set(webhook_ports)),'public_udp':PUBLIC_UDP,'peer_tcp':PEER_TCP,'peers':peers,'private_interface':interface,'rollback_timer_seconds':180}
        (root/'policy.json').write_text(json.dumps(manifest));print(json.dumps(manifest))
    except Exception:
        restore(root);subprocess.run(['systemctl','stop','notification-egress-rollback.timer'],check=False);raise

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--peers',nargs=3);p.add_argument('--interface',default='eth1');p.add_argument('--webhook-port',type=int,action='append');p.add_argument('--snapshot');p.add_argument('--apply',action='store_true');p.add_argument('--restore');a=p.parse_args()
    if a.restore:restore(a.restore)
    elif a.apply:apply(a.peers,a.interface,a.webhook_port or [443],a.snapshot)
    else:print(json.dumps(commands(a.peers,a.interface,a.webhook_port or [443])))
