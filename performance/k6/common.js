import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import exec from 'k6/execution';
import { Trend, Rate, Counter } from 'k6/metrics';
export const base = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const key = __ENV.API_KEY_FILE ? open(__ENV.API_KEY_FILE).trim() : __ENV.API_KEY;
if (!key) throw new Error('Set API_KEY_FILE (preferred) or API_KEY');
export const fixtures = __ENV.FIXTURES_FILE ? JSON.parse(open(__ENV.FIXTURES_FILE)) : {payments: [__ENV.PAYMENT_ID].filter(Boolean), refunds: []};
export const run = __ENV.RUN_ID || `loadtest-${Date.now()}`;
export const api = new Trend('create_api_ms', true);
export const completion = new Trend('completion_ms', true);
export const readTime = new Trend('read_ms', true);
export const listTime = new Trend('list_ms', true);
export const completed = new Rate('business_success');
export const accepted = new Counter('created_payments');
export const invalid = new Rate('unexpected_response');
export const baseOptions = {
  summaryTrendStats: ['med','p(95)','p(99)','max','count'],
  systemTags: ['status','method','name','scenario','expected_response'],
  thresholds: {
    http_req_failed: ['rate<0.01'], unexpected_response: ['rate<0.01'],
    ...(__ENV.POLL_COMPLETION === 'true' ? {business_success: ['rate==1']} : {}),
    read_ms: ['p(95)<200'], create_api_ms: ['p(95)<500'],
    http_req_duration: [{threshold:'p(99)<2000',abortOnFail:__ENV.ABORT_ON_LATENCY !== 'false',delayAbortEval:'20s'}],
  },
};
export function params(operation, idem, statuses) {
  return {headers: {'X-API-Key':key,'Content-Type':'application/json',...(idem ? {'Idempotency-Key':idem} : {})},
    tags:{operation,name:operation,phase:__ENV.PHASE || 'steady'}, timeout:'10s', ...(statuses ? {responseCallback:http.expectedStatuses(...statuses)} : {})};
}
export function request(method,path,body,operation,idem,statuses=[200]) {
  const r=http.request(method,base+path,body === null ? null : JSON.stringify(body),params(operation,idem,statuses));
  const ok=statuses.includes(r.status); invalid.add(!ok);
  check(r,{[operation+' expected status']:()=>ok});
  return r;
}
export function id(){return `${run}-${exec.scenario.name}-${exec.scenario.iterationInTest}`;}
export function paymentId(){if(!fixtures.payments.length)fail('Seed merchant-owned payment fixtures first');return fixtures.payments[exec.scenario.iterationInTest % fixtures.payments.length];}
export function read(pid=paymentId()) {const r=request('GET',`/api/v1/payments/${pid}`,null,'get_payment');readTime.add(r.timings.duration);return r;}
export function list(){const r=request('GET',`/api/v1/payments?page=${__ENV.PAGE || 0}&size=${__ENV.PAGE_SIZE || 20}`,null,'list_payments');listTime.add(r.timings.duration);return r;}
export function waitFor(path,start,operation){
 const until=Date.now()+Number(__ENV.COMPLETION_TIMEOUT_MS || 30000);
 while(Date.now()<until){sleep(Number(__ENV.POLL_SECONDS || 0.5));const r=request('GET',path,null,operation);
  if(r.status!==200)continue;const s=r.json('status');
  if(['SUCCESS','FAILED'].includes(s)){completion.add(Date.now()-start);completed.add(s==='SUCCESS');return s;}
 }completed.add(false);return 'TIMEOUT';
}
export function create(poll=__ENV.POLL_COMPLETION==='true') {
 const unique=id(),start=Date.now();const r=request('POST','/api/v1/payments',{userId:unique,amount:100,currency:'USD',paymentMethod:'UPI'},'create_payment',unique,[201]);
 api.add(r.timings.duration);if(r.status===201){accepted.add(1);if(poll)waitFor(`/api/v1/payments/${r.json('id')}`,start,'poll_payment');}return r;
}
export function mixed(){const n=exec.scenario.iterationInTest%10;if(n<7)read();else if(n<9)create();else list();}
export function pacing(){sleep(Number(__ENV.PACE_SECONDS || 0));}
export function arrival(defaultRate=10,defaultDuration='30s') {return {executor:'constant-arrival-rate',rate:Number(__ENV.RATE || defaultRate),timeUnit:'1s',duration:__ENV.DURATION || defaultDuration,preAllocatedVUs:Number(__ENV.VUS || 4),maxVUs:Number(__ENV.MAX_VUS || 12),gracefulStop:'35s'};}
export function summary(data){return {stdout:'PAYFLOW_SUMMARY='+JSON.stringify(data)+'\n'};}
