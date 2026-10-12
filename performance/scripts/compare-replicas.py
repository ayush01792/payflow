#!/usr/bin/env python3
"""Same offered workload at two, three, and capped-HPA replicas; local environment only."""
import subprocess,json,time,pathlib,os
r=pathlib.Path(__file__).resolve().parents[2];out=r/'performance/results';ns='payflow-performance'
if os.environ.get('PAYFLOW_NAMESPACE',ns)!=ns:raise SystemExit('Use the isolated performance namespace')
def k(*a):return subprocess.run(['kubectl','-n',ns,*a],capture_output=True,text=True,check=True).stdout
def fixed(n):
 k('patch','hpa','payflow','--type=merge','-p',json.dumps({'spec':{'minReplicas':n,'maxReplicas':n}}));k('scale','deployment','payflow','--replicas='+str(n));k('rollout','status','deployment/payflow','--timeout=600s')
 # Do not overlap terminating and new capacity on the constrained local node.
 deadline=time.time()+180
 while any(p['metadata'].get('deletionTimestamp') for p in json.loads(k('get','pods','-l','app.kubernetes.io/name=payflow','-o','json'))['items']):
  if time.time()>deadline:raise RuntimeError('Terminating application Pod did not exit')
  time.sleep(5)
def run(label,duration='180s'):
 subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label+'-before'],check=True)
 p=subprocess.Popen(['python3',str(r/'performance/scripts/run-kubernetes.py'),'payment-mixed.js',label,'RATE='+os.environ.get('SCALING_RATE','40'),'DURATION='+duration,'K6_NO_CONNECTION_REUSE=true',*(['ABORT_ON_LATENCY=false'] if label=='scaling-hpa' else [])])
 samples=[]
 while p.poll() is None:
  pods=json.loads(k('get','pods','-l','app.kubernetes.io/name=payflow','-o','json'));samples.append({'time':time.time(),'hpa':json.loads(k('get','hpa','payflow','-o','json')),'pods':pods})
  time.sleep(8)
 (out/(label+'-scaling.json')).write_text(json.dumps(samples,indent=2))
 subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label+'-after'],check=True)
 return p.returncode
try:
 fixed(2);run('scaling-fixed2')
 t=time.time();fixed(3);(out/'third-pod-ready.json').write_text(json.dumps({'scale_command':t,'rollout_ready':time.time(),'pods':json.loads(k('get','pods','-l','app.kubernetes.io/name=payflow','-o','json'))},indent=2))
 # Explicit startup warmup is not included in the steady comparison.
 run('scaling-three-warmup','60s');run('scaling-fixed3')
 fixed(2);time.sleep(20)
 k('patch','hpa','payflow','--type=merge','-p',json.dumps({'spec':{'minReplicas':2,'maxReplicas':3}}));run('scaling-hpa')
finally:fixed(2)
print('Replica experiments finished; isolated HPA restored to fixed two.',flush=True)
