import {baseOptions,mixed,pacing,summary} from './common.js';
export const options={...baseOptions,stages:JSON.parse(__ENV.STAGES || '[{"duration":"15s","target":2},{"duration":"5s","target":20},{"duration":"30s","target":20},{"duration":"10s","target":2},{"duration":"15s","target":2}]')};
export default function(){mixed();pacing();}
export const handleSummary=summary;
