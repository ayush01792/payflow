import {baseOptions,arrival,mixed,summary} from './common.js';
export const options={...baseOptions,scenarios:{work:arrival()}};
export default function(){mixed();}
export const handleSummary=summary;
