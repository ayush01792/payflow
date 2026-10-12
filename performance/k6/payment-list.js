import {baseOptions,arrival,list,summary} from './common.js';
export const options={...baseOptions,scenarios:{work:arrival(5,'30s')}};
export default function(){list();}
export const handleSummary=summary;
