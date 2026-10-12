import {baseOptions,arrival,read,summary} from './common.js';
export const options={...baseOptions,scenarios:{work:arrival()}};
export default function(){read();}
export const handleSummary=summary;
