import {check} from 'k6';
import {baseOptions,create,summary} from './common.js';
export const options={...baseOptions,thresholds:{http_req_failed:['rate<0.01'],checks:['rate==1'],business_success:['rate==1']},scenarios:{seed:{executor:'shared-iterations',vus:2,iterations:Number(__ENV.SEED_COUNT || 200),maxDuration:'15m'}}};
export default function(){const r=create(true);if(r.status===201)console.log('PAYFLOW_FIXTURE='+r.json('id'));}
export const handleSummary=summary;
