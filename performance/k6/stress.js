import exec from 'k6/execution';
import {Trend,Counter} from 'k6/metrics';
import {baseOptions,mixed,pacing,summary} from './common.js';
const stages=JSON.parse(__ENV.STAGES || '[{"duration":"20s","target":5},{"duration":"30s","target":15},{"duration":"30s","target":30},{"duration":"30s","target":60},{"duration":"20s","target":0}]');
if(stages.length>8)throw new Error('Use at most eight bounded stress stages');
const stageLatency=new Trend('stress_iteration_ms',true);
const stageOps=new Counter('stress_iterations');
const thresholds={...baseOptions.thresholds};
for(const s of stages)if(s.target>0)thresholds[`stress_iteration_ms{stage:${s.target}vu}`]=['p(95)<2000'];
export const options={...baseOptions,thresholds,stages};
function seconds(duration){const m=/^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(duration);if(!m)throw new Error('Invalid stage duration');return Number(m[1])*({ms:0.001,s:1,m:60,h:3600}[m[2]]);}
export default function(){
  const t=exec.instance.currentTestRunDuration/1000;let end=0,stage='cooldown';
  for(const s of stages){end+=seconds(s.duration);if(t<end){stage=s.target>0?`${s.target}vu`:'cooldown';break;}}
  const start=Date.now();mixed();stageLatency.add(Date.now()-start,{stage});stageOps.add(1,{stage});pacing();
}
export const handleSummary=summary;
