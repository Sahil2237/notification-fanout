#!/usr/bin/env python3
"""Validate/apply a bounded profile on an existing HA host; never recreate Redis or Sentinel."""
import argparse,json,math,os,pathlib,subprocess,tempfile
MIB=1024*1024

def validate(profile):
    if set(profile)!={'target_events_per_second','identity_ttl_seconds','application','redis','minimum_remaining_available_mib'}:
        raise ValueError('Unexpected capacity profile fields')
    rate=profile['target_events_per_second'];ttl=profile['identity_ttl_seconds']
    if type(rate) is not int or not 1<=rate<=1000 or ttl!=300:raise ValueError('Reviewed target is at most 1000/s with five-minute expiry')
    app=profile['application'];redis=profile['redis']
    if set(app)!={'CACHE_MAX_BYTES','CACHE_MAX_ENTRIES','REQUESTS_PER_MINUTE'}:raise ValueError('Only reviewed application settings may change')
    for key,low,high in [('CACHE_MAX_BYTES',65536,2*1024**3),('CACHE_MAX_ENTRIES',10,2000000),('REQUESTS_PER_MINUTE',rate*60,rate*90)]:
        if type(app[key]) is not int or not low<=app[key]<=high:raise ValueError('Invalid bounded application setting '+key)
    if app['CACHE_MAX_ENTRIES']<rate*ttl*2:raise ValueError('Capacity must allow two aliases per event')
    if app['CACHE_MAX_BYTES']<rate*ttl*2*1400:raise ValueError('Insufficient conservatively accounted identity budget')
    if set(redis)!={'maxmemory_mib','container_memory_mib','cpus'}:raise ValueError('Unexpected Redis resource setting')
    if type(redis['maxmemory_mib']) is not int or not 64<=redis['maxmemory_mib']<=1152:raise ValueError('Redis data memory outside reviewed range')
    if type(redis['container_memory_mib']) is not int or not redis['maxmemory_mib']+512<=redis['container_memory_mib']<=1792:raise ValueError('Redis needs at least 512 MiB process/replication/rewrite headroom')
    if not isinstance(redis['cpus'],(int,float)) or isinstance(redis['cpus'],bool) or not 0.5<=redis['cpus']<=1:raise ValueError('Redis CPU outside reviewed range')
    if type(profile['minimum_remaining_available_mib']) is not int or profile['minimum_remaining_available_mib']<384:raise ValueError('Keep host headroom')
    return profile

def memory_guard(profile,available_bytes,current_redis_bytes):
    validate(profile)
    increase=max(0,profile['redis']['container_memory_mib']*MIB-current_redis_bytes)
    remaining=available_bytes-increase
    if remaining<profile['minimum_remaining_available_mib']*MIB:raise ValueError('Insufficient host memory headroom; do not apply profile')
    return remaining

def application_configuration(inventory,compose,profile):
    validate(profile)
    import copy
    inventory=copy.deepcopy(inventory);compose=copy.deepcopy(compose)
    inventory.setdefault('application',{}).update(profile['application'])
    inventory.setdefault('redis',{}).setdefault('resources',{}).update(profile['redis'])
    for role in ('app','migrate'):
        compose['services'][role]['environment'].update({k:str(v) for k,v in profile['application'].items()})
    return inventory,compose

def atomic_json(path,value):
    path=pathlib.Path(path);temporary=path.with_name(path.name+'.capacity-next')
    temporary.write_text(json.dumps(value,indent=2)+'\n');temporary.chmod(0o600);temporary.replace(path)

def apply(profile,root):
    if os.geteuid()!=0:raise ValueError('Run on authorized HA host as root')
    validate(profile);root=pathlib.Path(root)
    if not (root/'enabled').is_file():raise ValueError('Existing HA installation required')
    inventory=json.loads((root/'inventory.json').read_text());compose=json.loads((root/'compose.yaml').read_text())
    redis_compose=json.loads((root/'redis/compose.yaml').read_text())
    info=dict(line.split(':',1) for line in pathlib.Path('/proc/meminfo').read_text().splitlines() if ':' in line)
    used=int(subprocess.check_output(['docker','exec','notification-rate-redis-1','sh','-c',"awk '/VmRSS:/ {print $2*1024}' /proc/1/status"],text=True).strip())
    remaining=memory_guard(profile,int(info['MemAvailable'].split()[0])*1024,used)
    backup=root/'capacity-original';backup.mkdir(mode=0o700,exist_ok=True)
    for source,name in [(root/'inventory.json','inventory.json'),(root/'compose.yaml','compose.yaml'),(root/'redis/compose.yaml','redis-compose.yaml')]:
        if not (backup/name).exists():
            import shutil
            shutil.copy2(source,backup/name);(backup/name).chmod(0o600)
    limits=profile['redis'];service=redis_compose['services']['redis']
    service.update(mem_limit=str(limits['container_memory_mib'])+'m',memswap_limit=str(limits['container_memory_mib'])+'m',cpus=limits['cpus'])
    subprocess.run(['docker','update','--memory',str(limits['container_memory_mib'])+'m','--memory-swap',str(limits['container_memory_mib'])+'m','--cpus',str(limits['cpus']),'notification-rate-redis-1'],check=True,stdout=subprocess.DEVNULL)
    acl=(root/'redis/redis/users.acl').read_text();password=next(line.split('>')[1].split()[0] for line in acl.splitlines() if line.startswith('user admin '))
    base=['docker','exec','-e','REDISCLI_AUTH','notification-rate-redis-1','redis-cli','--tls','--cacert','/tls/redis-ca.crt','-h','127.0.0.1','--user','admin','--no-auth-warning','--json']
    env=dict(os.environ,REDISCLI_AUTH=password)
    for args in [('CONFIG','SET','maxmemory',str(limits['maxmemory_mib']*MIB)),('CONFIG','REWRITE')]:
        result=subprocess.run(base+list(args),env=env,capture_output=True,text=True)
        if result.returncode or result.stdout.strip()!='"OK"':raise RuntimeError('Protected Redis tuning failed; inspect host state')
    inventory,compose=application_configuration(inventory,compose,profile)
    atomic_json(root/'inventory.json',inventory);atomic_json(root/'compose.yaml',compose);atomic_json(root/'redis/compose.yaml',redis_compose)
    atomic_json(root/'capacity-applied.json',profile)
    print(json.dumps({'capacity_configuration_written':True,'redis_not_restarted':True,'sentinel_state_unchanged':True,'remaining_available_mib_after_full_redis_reservation':remaining//MIB,'app_restart_required':True}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('profile');p.add_argument('--root',default='/opt/notification-fanout/ha');p.add_argument('--apply',action='store_true');a=p.parse_args()
    profile=validate(json.loads(pathlib.Path(a.profile).read_text()))
    if a.apply:apply(profile,a.root)
    else:print(json.dumps({'valid_profile':True,'profile':profile}))
