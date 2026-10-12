#!/usr/bin/env python3
"""Test CPU quota as a candidate, restoring one core unless explicitly adopted later."""
import subprocess,pathlib,json,os
r=pathlib.Path(__file__).resolve().parents[2];ns='payflow-performance'
if os.environ.get('PAYFLOW_NAMESPACE',ns)!=ns:raise SystemExit('Isolated namespace only')
def k(*a):return subprocess.run(['kubectl','-n',ns,*a],capture_output=True,text=True,check=True).stdout
def run(script,label,**settings):return subprocess.run(['python3',str(r/'performance/scripts/run-kubernetes.py'),script,label,*[f'{k}={v}' for k,v in settings.items()]]).returncode
def collect(label):subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label],check=True)
def quota(n):
 k('patch','deployment','payflow','--type=strategic','-p',json.dumps({'spec':{'template':{'spec':{'containers':[{'name':'payflow','resources':{'limits':{'cpu':str(n)}}}]}}}}));k('rollout','status','deployment/payflow','--timeout=600s')
try:
 run('payment-mixed.js','cpu1-warmup',RATE=10,DURATION='60s',ABORT_ON_LATENCY='false')
 collect('cpu1-before');run('payment-mixed.js','cpu1-mixed',RATE=10,DURATION='60s');collect('cpu1-mixed-after')
 run('stress.js','cpu1-stress');collect('cpu1-after')
 quota(2)
 run('payment-mixed.js','cpu2-warmup',RATE=10,DURATION='60s',ABORT_ON_LATENCY='false')
 collect('cpu2-before');run('payment-mixed.js','cpu2-mixed',RATE=10,DURATION='60s');collect('cpu2-mixed-after')
 run('stress.js','cpu2-stress');collect('cpu2-after')
finally:quota(1)
collect('cpu-comparison-complete')
print('CPU comparison complete; original one-core quota restored pending evidence review.',flush=True)
