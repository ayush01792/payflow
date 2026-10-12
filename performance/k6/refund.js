import exec from 'k6/execution';
import {baseOptions,fixtures,request,id,waitFor,summary} from './common.js';
export const options={...baseOptions,thresholds:{...baseOptions.thresholds,business_success:['rate==1']},scenarios:{refunds:{executor:'shared-iterations',vus:2,iterations:fixtures.refunds.length,maxDuration:'5m'}}};
export default function(){const pid=fixtures.refunds[exec.scenario.iterationInTest],start=Date.now();
const r=request('POST',`/api/v1/payments/${pid}/refunds`,{amount:10,merchantRefundReference:id()},'create_refund',id(),[202]);
if(r.status===202)waitFor(`/api/v1/refunds/${r.json('id')}`,start,'get_refund');}
export const handleSummary=summary;
