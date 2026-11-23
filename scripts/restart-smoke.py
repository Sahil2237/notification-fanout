#!/usr/bin/env python3
"""Development-only: restarts this project's local Java process, never a database."""
import json,os,pathlib,subprocess,time,urllib.request
root=pathlib.Path('/workspaces/notification-fanout');os.chdir(root)
base='http://localhost:8080'
token=os.environ.get('API_TOKEN','local-demo-token-change-me')
def api(method,path,body=None):
    data=None if body is None else json.dumps(body).encode()
    request=urllib.request.Request(base+path,data=data,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=5) as response:
        raw=response.read();return json.loads(raw) if raw else None

def wait_ready():
    for _ in range(150):
        try:
            if api('GET','/health/ready')['status']=='ready':return
        except OSError:pass
        time.sleep(.2)
    raise RuntimeError('Local service did not become ready')

# Only the immutable local JAR copies launched by start-local.sh are selected.
subprocess.run(['pkill','-TERM','-f','^java -jar .run/notification-'],check=False)
time.sleep(2)
with (root/'.run/restart-api-only.log').open('w') as log:
    process=subprocess.Popen(['./scripts/start-local.sh'],env={**os.environ,'WORKER_ENABLED':'false'},stdout=log,stderr=log)
wait_ready()
subscription=api('POST','/v1/subscriptions',{'webhookUrl':'http://127.0.0.1:9090/hook','filter':{'type':'restart.probe'}})
event=api('POST','/v1/events',{'type':'restart.probe','source':'recovery-check','payload':{'check':'persisted-before-process-exit'}})['event']
path='/v1/events/'+event['id']+'/deliveries'
before=api('GET',path)['items']
assert len(before)==1 and before[0]['status']=='pending' and before[0]['attemptCount']==0
process.kill();process.wait(timeout=10)
started=time.monotonic()
with (root/'.run/restart-worker.log').open('w') as log:
    subprocess.Popen(['./scripts/start-local.sh'],env={**os.environ,'WORKER_ENABLED':'true'},stdout=log,stderr=log,start_new_session=True)
wait_ready()
for _ in range(150):
    after=api('GET',path)['items'][0]
    if after['status']!='pending':break
    time.sleep(.2)
assert after['status']=='delivered',after
api('DELETE','/v1/subscriptions/'+subscription['id'])
result={'eventId':event['id'],'beforeRestart':'pending','afterRestart':after['status'],
        'attempts':after['attemptCount'],'restartToDeliveredSeconds':time.monotonic()-started}
(root/'restart-result.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
