import http from 'k6/http';
import {check} from 'k6';
import {baseOptions,base,run,params,summary} from './common.js';
export const options={...baseOptions,thresholds:{http_req_failed:['rate<0.01'],checks:['rate==1']},vus:1,iterations:1};
export default function(){
const body={userId:run,amount:100,currency:'USD',paymentMethod:'UPI'};
const batch=(bodies,key)=>http.batch(bodies.map(b=>['POST',base+'/api/v1/payments',JSON.stringify(b),params('idem_create',key,[200,201,409])]));
const same=batch(Array(10).fill(body),run+'-same');
check(same,{'same payload: one ID, one 201':rs=>rs.every(r=>[200,201].includes(r.status)) && new Set(rs.map(r=>r.json('id'))).size===1 && rs.filter(r=>r.status===201).length===1});
const conflict=batch(Array.from({length:10},(_,i)=>({...body,amount:100+i})),run+'-conflict');
check(conflict,{'conflicting payloads: one winner and nine 409':rs=>rs.filter(r=>r.status===201).length===1 && rs.filter(r=>r.status===409).length===9});
}
export const handleSummary=summary;
