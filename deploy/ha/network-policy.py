#!/usr/bin/env python3
"""Restrict existing host-network proxies to the VPC without changing SSH or cluster rules."""
import argparse,ipaddress,json,os,pathlib,re,shutil,subprocess
PORTS=(80,443,9080)

def policy(private_ip,vpc_cidr,interface):
    address=ipaddress.ip_address(private_ip);network=ipaddress.ip_network(vpc_cidr,strict=True)
    private_ranges=[ipaddress.ip_network(value) for value in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')]
    if address.version!=4 or network.version!=4 or not any(network.subnet_of(value) for value in private_ranges) or address not in network or network.prefixlen<16:
        raise ValueError('A private IPv4 VPC and member host address are required')
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{0,14}',interface) or interface=='lo':raise ValueError('Invalid VPC interface')
    return [['ufw','allow','in','on',interface,'proto','tcp','from',str(network),'to',str(address),'port',str(port)] for port in PORTS]

def obsolete_rules(added,vpc_cidr):
    rules=set(added.splitlines());result=[]
    for port in PORTS:
        if 'ufw allow '+str(port)+'/tcp' in rules:result.append(['ufw','--force','delete','allow',str(port)+'/tcp'])
    old='ufw allow from '+vpc_cidr+' to any port 9080 proto tcp'
    if old in rules:result.append(['ufw','--force','delete','allow','from',vpc_cidr,'to','any','port','9080','proto','tcp'])
    return result

def run(args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)

def restore(root):
    root=pathlib.Path(root)
    for name in ('user.rules','user6.rules'):
        if not (root/name).is_file():raise ValueError('Rollback snapshot missing')
    for name in ('user.rules','user6.rules'):shutil.copy2(root/name,pathlib.Path('/etc/ufw')/name)
    run(['ufw','reload'])

def apply(private_ip,vpc_cidr,interface,root):
    commands=policy(private_ip,vpc_cidr,interface)
    if os.geteuid()!=0:raise ValueError('Root operator required')
    if 'Status: active' not in run(['ufw','status']):raise ValueError('Existing active UFW required')
    defaults=pathlib.Path('/etc/default/ufw').read_text()
    if 'DEFAULT_INPUT_POLICY="DROP"' not in defaults or 'IPV6=yes' not in defaults:raise ValueError('IPv4/IPv6 default-deny policy required')
    routes=json.loads(run(['ip','-j','-4','route','show','default']))
    if any(route.get('dev')==interface for route in routes):raise ValueError('VPC interface must not be the public default-route interface')
    addresses=json.loads(run(['ip','-j','-4','address','show','dev',interface]))
    if not any(a.get('local')==private_ip for entry in addresses for a in entry.get('addr_info',[])):raise ValueError('Private IP is not configured on VPC interface')
    host=pathlib.Path('/opt/notification-fanout/ha')
    compose=json.loads((host/'compose.yaml').read_text())
    if compose['services']['proxy'].get('network_mode')!='host':raise ValueError('UFW policy requires host-network proxy; Docker published ports need separate forwarding controls')
    root=pathlib.Path(root)
    if root.exists():raise ValueError('Use a new protected snapshot directory')
    root.mkdir(parents=True,mode=0o700)
    for name in ('user.rules','user6.rules'):shutil.copy2(pathlib.Path('/etc/ufw')/name,root/name)
    script=root/'network-policy.py';shutil.copy2(__file__,script);script.chmod(0o700)
    added=run(['ufw','show','added']);(root/'rules-before.txt').write_text(added)
    # The independent timer restores access if the caller loses its session before verification.
    run(['systemd-run','--unit=notification-network-rollback','--on-active=180s','/usr/bin/python3',str(script),'--restore',str(root)])
    try:
        for command in commands:run(command)
        for command in obsolete_rules(added,vpc_cidr):run(command)
        current=run(['ufw','show','added'])
        if obsolete_rules(current,vpc_cidr):raise RuntimeError('Broad proxy allowance remains')
        (root/'policy.json').write_text(json.dumps({'private_ip':private_ip,'vpc_cidr':vpc_cidr,'interface':interface,'ports':PORTS}))
        print(json.dumps({'host_vpc_ingress_applied':True,'rollback_timer_seconds':180,'ssh_rules_unchanged':True,'cluster_rules_unchanged':True,'cloud_firewall_changed':False}))
    except Exception:
        restore(root);subprocess.run(['systemctl','stop','notification-network-rollback.timer'],check=False);raise

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--private-ip');parser.add_argument('--vpc-cidr',default='10.122.0.0/20');parser.add_argument('--interface',default='eth1');parser.add_argument('--snapshot');parser.add_argument('--apply',action='store_true');parser.add_argument('--restore');args=parser.parse_args()
    if args.restore:restore(args.restore)
    elif args.apply:apply(args.private_ip,args.vpc_cidr,args.interface,args.snapshot)
    else:print(json.dumps({'allow_before_removing_public_rules':policy(args.private_ip,args.vpc_cidr,args.interface),'ssh_and_cluster_rules':'preserved','cloud_firewall':'separate load-balancer-UID restriction required'}))
