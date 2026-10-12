import {baseOptions,arrival,mixed,summary} from './common.js';
export const options={...baseOptions,scenarios:{soak:arrival(2,'15m')}};
export default function(){mixed();}
export const handleSummary=summary;
