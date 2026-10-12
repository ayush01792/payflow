#!/usr/bin/env python3
"""Small provider delay/transient-failure runs; controls reset on every Pod in finally."""
import subprocess,pathlib,json,time,os
r=pathlib.Path(__file__).resolve().parents[2];ns=os.environ.get('PAYFLOW_NAMESPACE','payflow-performance')
if ns!='payflow-performance':raise SystemExit('Use the isolated namespace')
def k(*args):return subprocess.run(['kubectl','-n',ns,*args],capture_output=True,text=True,check=True).stdout
def configure(body):
 pods=json.loads(k('get','pods','-l','app.kubernetes.io/name=payflow','-o','json'))['items']
 for pod in pods:
  if pod.get('metadata',{}).get('deletionTimestamp'):continue
  k('exec',pod['metadata']['name'],'--','curl','-fsS','--max-time','5','-X','PUT','-H','Content-Type: application/json','-d',json.dumps(body),'http://localhost:8080/mock/providers/provider-a/behavior')
for label,body in [('provider-delay',{'scenario':'SUCCESS','failFirst':0,'delayMs':300}),('provider-transient',{'scenario':'HTTP_500','failFirst':1,'delayMs':0}),('provider-timeout',{'scenario':'TIMEOUT','failFirst':0,'delayMs':3000})]:
 try:
  configure(body)
  subprocess.run(['python3',str(r/'performance/scripts/run-kubernetes.py'),'payment-create.js',label,'RATE=1','DURATION=10s','POLL_COMPLETION=true'],check=False)
  subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label],check=True)
 finally:configure({'scenario':'SUCCESS','failFirst':0,'delayMs':0})
 # Give open circuit and pending work time to recover before the next distinct failure mode.
 time.sleep(12)
