#!/usr/bin/env python3
"""Run on the Droplet as its operator; generates only synthetic, non-matching capacity events."""
import argparse, concurrent.futures, datetime, json, pathlib, statistics, subprocess, time, urllib.request, uuid
parser=argparse.ArgumentParser();parser.add_argument('--events',type=int,default=10000);parser.add_argument('--rate',type=int,default=50)
args=parser.parse_args()
if not 1<=args.events<=10000 or not 1<=args.rate<=50:raise SystemExit('Probe is bounded to 10,000 events and 50 submissions/s')
root=pathlib.Path('/opt/notification-fanout');settings=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
headers={'Authorization':'Bearer '+settings['API_TOKEN'],'Content-Type':'application/json'}
run_id=uuid.uuid4().hex;event_type='assessment.capacity.'+run_id
for offset in range(0,1100,100):
    request=urllib.request.Request('http://127.0.0.1:8080/v1/subscriptions?limit=100&offset='+str(offset),headers=headers)
    with urllib.request.urlopen(request,timeout=10) as response:page=json.load(response)
    for subscription in page['items']:
        rules=subscription['filter']
        if rules.get('type') in (None,event_type) and rules.get('source') in (None,'assessment-load'):
            raise SystemExit('A subscription could match the capacity probe; refusing external fanout')
    if not page['hasMore']:break
latencies=[];failures=[];ids=set();started=time.monotonic()
def send(index):
    begin=time.monotonic()
    data={'type':event_type,'source':'assessment-load','payload':{'synthetic':True,'runId':run_id,'index':index}}
    request=urllib.request.Request('http://127.0.0.1:8080/v1/events',data=json.dumps(data).encode(),headers=headers|{'Idempotency-Key':'capacity-'+run_id+'-'+str(index)})
    try:
        with urllib.request.urlopen(request,timeout=10) as response:
            assert response.status==202
            return (time.monotonic()-begin)*1000,json.load(response)['event']['id'],None
    except Exception as error:return (time.monotonic()-begin)*1000,None,type(error).__name__
with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
    futures=[]
    for index in range(args.events):
        delay=started+index/args.rate-time.monotonic()
        if delay>0:time.sleep(delay)
        futures.append(pool.submit(send,index))
        if index and index%1000==0:print('Submitted '+str(index)+' synthetic no-match events',flush=True)
    for future in futures:
        latency,id,error=future.result();latencies.append(latency)
        if error:failures.append(error)
        else:ids.add(id)
acceptance_seconds=time.monotonic()-started
release=(root/'current').resolve()
compose=['docker','compose','--project-name','notifications','--env-file',str(root/'.env'),'-f',str(release/'compose.yaml'),'exec','-T','db','psql','-U','notification_owner','-d','notifications','-Atc']
# event_type is generated internally from a fixed prefix plus a UUID, never user-provided SQL.
query="SELECT count(*),count(o.dispatched_at),count(d.id) FROM events e JOIN event_outbox o ON o.event_id=e.id LEFT JOIN deliveries d ON d.event_id=e.id WHERE e.type='"+event_type+"'"
import os
env=os.environ|{'APP_IMAGE':'notification-fanout:'+release.name}
for attempt in range(180):
    accepted,dispatched,deliveries=map(int,subprocess.check_output(compose+[query],env=env,text=True).strip().split('|'))
    if dispatched==len(ids):break
    time.sleep(1)
ordered=sorted(latencies)
def percentile(p):return round(ordered[min(len(ordered)-1,int((len(ordered)-1)*p))],2)
report={'run_id':run_id,'release_sha':release.name,'event_type':event_type,'requested':args.events,'accepted_http':len(ids),'persisted':accepted,'dispatched':dispatched,'delivery_jobs':deliveries,'failures':failures,'offered_events_per_second':args.rate,'acceptance_duration_seconds':round(acceptance_seconds,2),'acceptance_latency_ms':{'p50':percentile(.5),'p95':percentile(.95),'p99':percentile(.99)},'scope':'single-type synthetic HTTP ingestion and Kafka dispatch, zero matching subscribers; not a fanout throughput SLO','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(root/'capacity-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
assert not failures and accepted==dispatched==len(ids)==args.events and deliveries==0
