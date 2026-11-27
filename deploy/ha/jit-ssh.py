#!/usr/bin/env python3
"""Open/close this CI runner's single-IP SSH allowance on the pinned assessment firewall."""
import argparse,ipaddress,json,os,pathlib,time,urllib.request,urllib.error
FIREWALL_ID='9ebfb734-0687-4487-babd-8853bc73bb97'
DROPLETS={622664599,622797519,622797520}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def cidr(value):
    address=ipaddress.ip_address(value)
    if address.version!=4 or not address.is_global:raise ValueError('Public IPv4 runner address required')
    return str(address)+'/32'

def rule(address):return {'protocol':'tcp','ports':'22','sources':{'addresses':[address]}}

def exact(item,address):
    sources=item.get('sources',{})
    return item.get('protocol')=='tcp' and item.get('ports')=='22' and item.get('action','allow')=='allow' and sources.get('addresses')==[address] and all(not value for key,value in sources.items() if key!='addresses')

class Api:
    def __init__(self,token):
        if not token or len(token)<32:raise ValueError('Dedicated firewall token required')
        self.token=token;self.opener=urllib.request.build_opener(NoRedirect())
    def call(self,method,suffix='',payload=None):
        if suffix not in ('','/rules'):raise ValueError('Unexpected API path')
        request=urllib.request.Request('https://api.digitalocean.com/v2/firewalls/'+FIREWALL_ID+suffix,data=None if payload is None else json.dumps(payload).encode(),headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json'},method=method)
        try:
            with self.opener.open(request,timeout=10) as response:
                content=response.read(1048577)
                if len(content)>1048576:raise RuntimeError('API response too large')
                return json.loads(content) if content else {}
        except urllib.error.HTTPError as error:raise RuntimeError('Firewall API HTTP '+str(error.code)) from None
    def firewall(self):
        value=self.call('GET')['firewall']
        if value['id']!=FIREWALL_ID or set(value.get('droplet_ids',[]))!=DROPLETS:raise ValueError('Unexpected firewall membership')
        return value

def save(path,state):
    path=pathlib.Path(path);descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'w') as output:json.dump(state,output)

def wait_ready(api,address,present):
    for attempt in range(10):
        value=api.firewall();changes=value.get('pending_changes',[])
        if any(change.get('status')=='failed' for change in changes):raise RuntimeError('Firewall propagation failed')
        waiting=value.get('status')=='waiting' or any(change.get('status')!='succeeded' for change in changes)
        if not waiting and any(exact(item,address) for item in value.get('inbound_rules',[]))==present:return
        if attempt<9:time.sleep(2)
    raise RuntimeError('Firewall propagation did not finish in the bounded window')

def open_window(api,address,path):
    address=cidr(address);value=api.firewall();exists=any(exact(item,address) for item in value.get('inbound_rules',[]))
    state={'firewall_id':FIREWALL_ID,'address':address,'owned':not exists,'created_at':int(time.time())}
    # Persist intent before POST so cleanup covers an acknowledged-or-uncertain write.
    save(path,state)
    if not exists:api.call('POST','/rules',{'inbound_rules':[rule(address)]})
    wait_ready(api,address,True)
    return state

def close_window(api,path):
    path=pathlib.Path(path)
    if not path.exists():return {'cleanup':'no-window-state'}
    state=json.loads(path.read_text())
    if state.get('firewall_id')!=FIREWALL_ID or cidr(state['address'].split('/')[0])!=state['address']:raise ValueError('Invalid window state')
    if state.get('owned') is not True:return {'cleanup':'preexisting-rule-preserved'}
    value=api.firewall()
    if any(exact(item,state['address']) for item in value.get('inbound_rules',[])):api.call('DELETE','/rules',{'inbound_rules':[rule(state['address'])]})
    wait_ready(api,state['address'],False)
    return {'cleanup':'owned-rule-removed','address':state['address']}

def runner_ip():
    opener=urllib.request.build_opener(NoRedirect())
    with opener.open('https://api.ipify.org',timeout=10) as response:value=response.read(65).decode().strip()
    cidr(value);return value

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['open','close']);p.add_argument('--state',required=True);a=p.parse_args()
    try:
        if a.action=='close' and not pathlib.Path(a.state).exists():print(json.dumps({'cleanup':'no-window-state'}))
        else:
            api=Api(os.environ.get('DO_FIREWALL_TOKEN',''))
            result=open_window(api,runner_ip(),a.state) if a.action=='open' else close_window(api,a.state)
            print(json.dumps(result))
    except Exception as error:
        # Do not expose API response bodies, bearer values or parser source snippets.
        raise SystemExit('CI SSH firewall '+a.action+' failed ('+type(error).__name__+'); inspect scoped API access and window state') from None
