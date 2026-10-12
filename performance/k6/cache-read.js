import exec from 'k6/execution';
import {baseOptions,fixtures,read,summary} from './common.js';
export const options={...baseOptions,scenarios:{cache:{executor:'shared-iterations',vus:Number(__ENV.VUS || 4),iterations:fixtures.payments.length,maxDuration:'5m'}}};
export default function(){read(fixtures.payments[exec.scenario.iterationInTest]);}
export const handleSummary=summary;
