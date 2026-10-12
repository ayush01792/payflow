import {sleep} from 'k6';
import {baseOptions,request,read,create,summary} from './common.js';
export const options={...baseOptions,thresholds:{...baseOptions.thresholds,business_success:['rate==1']},vus:1,duration:__ENV.DURATION || '30s'};
export default function(){request('GET','/api/v1/health',null,'health');read();create(true);sleep(1);}
export const handleSummary=summary;
