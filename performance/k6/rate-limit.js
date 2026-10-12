import exec from 'k6/execution';
import {check} from 'k6';
import {baseOptions,request,id,summary} from './common.js';
import {Counter} from 'k6/metrics';
const allowed=new Counter('limit_allowed'),rejected=new Counter('limit_rejected');
const limit=Number(__ENV.EXPECTED_LIMIT || 10),attempts=limit+5;
export const options={...baseOptions,noConnectionReuse:true,thresholds:{http_req_failed:['rate==0'],checks:['rate==1'],limit_allowed:[`count==${limit}`],limit_rejected:[`count==${attempts-limit}`]},scenarios:{limiter:{executor:'shared-iterations',vus:4,iterations:attempts,maxDuration:'30s'}}};
export default function(){const r=request('POST','/api/v1/payments',{userId:id(),amount:100,currency:'USD',paymentMethod:'UPI'},'rate_limit',id(),[201,429]);
if(r.status===201)allowed.add(1);if(r.status===429){rejected.add(1);check(r,{'Retry-After positive':r=>Number(r.headers['Retry-After'])>0});}}
export const handleSummary=summary;
