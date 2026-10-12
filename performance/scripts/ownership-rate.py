#!/usr/bin/env python3
"""Second-merchant ownership and shared limiter checks, isolated namespace only."""
import subprocess,pathlib,json,os
r=pathlib.Path(__file__).resolve().parents[2];ns='payflow-performance'
if os.environ.get('PAYFLOW_NAMESPACE',ns)!=ns:raise SystemExit('Isolated namespace only')
def k(*a):return subprocess.run(['kubectl','-n',ns,*a],capture_output=True,text=True,check=True).stdout
def run(script,label,**settings):
 e=os.environ.copy();e['API_KEY_FILE']=str(r/'secrets/performance-rate.api-key')
 subprocess.run(['python3',str(r/'performance/scripts/run-kubernetes.py'),script,label,*[f'{a}={b}' for a,b in settings.items()]],env=e,check=True)
def limit(n):
 k('patch','configmap','payflow-config','--type=merge','-p',json.dumps({'data':{'PAYMENT_CREATE_RATE_LIMIT':str(n)}}));k('rollout','restart','deployment/payflow');k('rollout','status','deployment/payflow','--timeout=600s')
if not (r/'secrets/performance-rate.api-key').exists():subprocess.run(['bash',str(r/'scripts/k8s-bootstrap-merchant.sh'),'loadtest-rate',str(r/'secrets/performance-rate.api-key')],env={**os.environ,'PAYFLOW_NAMESPACE':ns},check=True)
# Prime with the owning merchant, not the second merchant's credential.
subprocess.run(['python3',str(r/'performance/scripts/run-kubernetes.py'),'cache-read.js','ownership-prime','VUS=4'],env={**os.environ,'API_KEY_FILE':str(r/'secrets/performance.api-key')},check=True)
run('ownership.js','ownership-warm')
ids=json.loads((r/'performance/results/fixtures.json').read_text())['payments'][:3]
subprocess.run(['kubectl','-n','payflow','exec','deployment/redis','--','redis-cli','-n','1','DEL',*['payflow:payment:'+i for i in ids]],capture_output=True,check=True)
run('ownership.js','ownership-cold')
try:
 limit(10)
 # Reset only this disposable merchant's earlier ownership-check window.
 subprocess.run(['kubectl','-n','payflow','exec','deployment/redis','--','redis-cli','-n','1','DEL','payflow:rate:create-payment:loadtest-rate'],capture_output=True,check=True)
 subprocess.run(['python3',str(r/'performance/scripts/collect.py'),'rate-before'],check=True)
 run('rate-limit.js','rate-shared',EXPECTED_LIMIT=10)
 subprocess.run(['python3',str(r/'performance/scripts/collect.py'),'rate-after'],check=True)
finally:limit(100000)
print('Ownership and shared limiter checks completed; performance limit restored.',flush=True)
