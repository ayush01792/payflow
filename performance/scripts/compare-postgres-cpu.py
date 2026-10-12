#!/usr/bin/env python3
"""Bounded local database-quota candidate; no traffic while PostgreSQL restarts."""
import subprocess,pathlib,json,os,time
r=pathlib.Path(__file__).resolve().parents[2];ns='payflow-performance'
if os.environ.get('PAYFLOW_NAMESPACE',ns)!=ns:raise SystemExit('Isolated namespace only')
def k(*a):return subprocess.run(['kubectl',*a],capture_output=True,text=True,check=True).stdout
def sql(q):return k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',q).strip()
def drain():
 for _ in range(96):
  if sql("SELECT (SELECT count(*) FROM outbox_events WHERE status<>'PUBLISHED')+(SELECT count(*) FROM payments WHERE status IN ('CREATED','PROCESSING','PENDING_CONFIRMATION')); ")=='0':return
  time.sleep(5)
 raise RuntimeError('Work did not drain; database will not be restarted')
def quota(cpu):
 drain();k('-n','payflow','patch','statefulset','postgres','--type=strategic','-p',json.dumps({'spec':{'template':{'spec':{'containers':[{'name':'postgres','resources':{'limits':{'cpu':cpu}}}]}}}}));k('-n','payflow','rollout','status','statefulset/postgres','--timeout=180s')
 k('-n',ns,'wait','--for=condition=Ready','pod','-l','app.kubernetes.io/name=payflow,app.kubernetes.io/component=api','--timeout=300s')
def run(label,duration):
 return subprocess.run(['python3',str(r/'performance/scripts/run-kubernetes.py'),'payment-mixed.js',label,'RATE=40','DURATION='+duration,'ABORT_ON_LATENCY=false']).returncode
def collect(label):subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label],check=True)
original=json.loads(k('-n','payflow','get','statefulset','postgres','-o','json'))['spec']['template']['spec']['containers'][0]['resources']['limits']['cpu']
if original not in ['500m','1']:raise SystemExit('Expected known local PostgreSQL quota (500m or 1)')
try:
 if original!='500m':quota('500m')
 run('pg500-warmup','60s');collect('pg500-before');run('pg500-mixed','60s');collect('pg500-after');drain()
 quota('1')
 run('pg1000-warmup','60s');collect('pg1000-before');run('pg1000-mixed','60s');collect('pg1000-after');drain()
finally:quota(original)
collect('postgres-cpu-comparison-complete');print('Database candidate complete; original quota restored.',flush=True)
