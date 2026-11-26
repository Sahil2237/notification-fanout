// k6 open-arrival load test. Run against a disposable environment first.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
const base = __ENV.BASE_URL || 'http://localhost:8080';
if (!__ENV.API_TOKEN) throw new Error('API_TOKEN environment variable is required');
if (!/^http:\/\/(localhost|127\.0\.0\.1|app)(:|\/|$)/.test(base) && __ENV.ALLOW_REMOTE_LOAD !== 'true')
  throw new Error('Remote load requires ALLOW_REMOTE_LOAD=true and a capacity-reviewed environment');
const run = __ENV.RUN_ID || String(Date.now());
const accepted = new Counter('accepted_new_events');
const duplicates = new Counter('duplicate_events');
export const options = {
  scenarios: { ingestion: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 10000), timeUnit: '1s',
    duration: __ENV.DURATION || '60s', preAllocatedVUs: Number(__ENV.INITIAL_VUS || 100),
    maxVUs: Number(__ENV.MAX_VUS || 1000), gracefulStop: '15s'
  } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(99)<500'], dropped_iterations: ['count==0'] }
};
export default function () {
  const key = `load:${run}:${__VU}:${__ITER}`;
  const payload = JSON.stringify({type:__ENV.EVENT_TYPE || 'load.synthetic',source:'capacity-test',payload:{synthetic:true,run,sequence:key}});
  const response = http.post(base + '/v1/events',payload,{
    headers:{Authorization:'Bearer '+__ENV.API_TOKEN,'Content-Type':'application/json','Idempotency-Key':key},
    timeout:'10s', tags:{name:'POST /v1/events'}
  });
  if(check(response,{'accepted':r=>r.status===202})) {
    const body=response.json();
    if(body.duplicate) duplicates.add(1); else accepted.add(1);
  }
}
