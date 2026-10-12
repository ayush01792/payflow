import {check} from 'k6';
import {baseOptions,fixtures,request,create,summary} from './common.js';
export const options={...baseOptions,thresholds:{http_req_failed:['rate==0'],checks:['rate==1'],business_success:['rate==1']},vus:1,iterations:1};
// Use a second synthetic merchant's credential with the primary merchant's fixture IDs.
export default function(){
 for(const id of fixtures.payments.slice(0,3)){
  const r=request('GET',`/api/v1/payments/${id}`,null,'other_merchant_payment',undefined,[404]);
  check(r,{'another merchant cannot retrieve payment':r=>r.status===404});
 }
 const r=create(true);
 if(r.status===201){const own=request('GET',`/api/v1/payments/${r.json('id')}`,null,'owned_payment');check(own,{'merchant can retrieve own payment':r=>r.status===200});}
}
export const handleSummary=summary;
