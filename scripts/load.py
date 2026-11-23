#!/usr/bin/env python3
"""Bounded acceptance probe; outputs measurements, never a capacity guarantee."""
import argparse
import concurrent.futures
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid

parser=argparse.ArgumentParser()
parser.add_argument("--requests", type=int, default=100)
parser.add_argument("--concurrency", type=int, default=4)
args=parser.parse_args()
if not 1 <= args.requests <= 10000 or not 1 <= args.concurrency <= 64:
    parser.error("requests must be 1..10000; concurrency 1..64")
base=os.environ.get("API_BASE","http://localhost:8080")
token=os.environ.get("API_TOKEN","local-demo-token-change-me")
def send(_):
    payload=json.dumps({"type":"load.probe","source":"load-test","payload":{"data":"x"*1024}}).encode()
    request=urllib.request.Request(base+"/v1/events",data=payload,headers={"Content-Type":"application/json","Authorization":"Bearer "+token,"Idempotency-Key":str(uuid.uuid4())})
    start=time.perf_counter()
    try:
        with urllib.request.urlopen(request,timeout=15) as response:
            response.read();status=response.status
    except urllib.error.HTTPError as error:
        status=error.code
    except OSError:
        status=0
    return status,(time.perf_counter()-start)*1000
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
    results=list(executor.map(send,range(args.requests)))
elapsed=time.perf_counter()-start
latencies=sorted(duration for status,duration in results if status==202)
print(json.dumps({"requests":args.requests,"concurrency":args.concurrency,"seconds":elapsed,
    "accepted":len(latencies),"failures":sum(status!=202 for status,_ in results),
    "acceptedPerSecond":len(latencies)/elapsed,
    "p95AcceptanceMs":latencies[math.ceil(len(latencies)*.95)-1] if latencies else None},indent=2))
