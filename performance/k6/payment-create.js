import {baseOptions,arrival,create,summary} from './common.js';
export const options={...baseOptions,scenarios:{work:arrival()}};
export default function(){create();}
export const handleSummary=summary;
