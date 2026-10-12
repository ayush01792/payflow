#!/usr/bin/env python3
"""Prepare/restore the documented sequential local performance environment. No secret output."""
import argparse,json,pathlib,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','restore']);a=p.parse_args()
def k(*args,input=None):
 return subprocess.run(['kubectl',*args],input=input,text=True,capture_output=True,check=True).stdout
if k('config','current-context').strip()!='kind-payflow':sys.exit('This helper is limited to the existing kind-payflow local cluster.')
ns='payflow-performance';out=root/'performance/results';out.mkdir(exist_ok=True)
if a.action=='prepare':
 d=json.loads(k('-n','payflow','get','deployment','payflow','-o','json'))
 h=json.loads(k('-n','payflow','get','hpa','payflow','-o','json'))
 h['metadata']={key:h['metadata'][key] for key in ['name','namespace']};h.pop('status',None)
 if (out/'environment-state.json').exists():sys.exit('An environment-state.json already exists: restore the previous run before preparing again.')
 pg=json.loads(k('-n','payflow','get','statefulset','postgres','-o','json'))
 (out/'environment-state.json').write_text(json.dumps({'replicas':d['spec']['replicas'],'hpa':h,'postgres_args':pg['spec']['template']['spec']['containers'][0].get('args')},indent=2))
 # One active DB's consumers only. Stop normal replicas first and wait for termination.
 k('-n','payflow','delete','hpa','payflow');k('-n','payflow','scale','deployment','payflow','--replicas=0')
 k('-n','payflow','wait','--for=delete','pod','-l','app.kubernetes.io/name=payflow,app.kubernetes.io/component=api','--timeout=180s')
 k('-n','payflow','patch','statefulset','postgres','--type=strategic','-p',json.dumps({'spec':{'template':{'spec':{'containers':[{'name':'postgres','args':['-c','shared_preload_libraries=pg_stat_statements','-c','pg_stat_statements.track=all']}]}}}}))
 k('-n','payflow','rollout','status','statefulset/postgres','--timeout=180s')
 exists=k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','postgres','-Atc',"SELECT 1 FROM pg_database WHERE datname='payflow_performance';").strip()
 if not exists:k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','postgres','-c','CREATE DATABASE payflow_performance OWNER payflow;')
 k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-c','CREATE EXTENSION IF NOT EXISTS pg_stat_statements;')
 k('apply','-f','-',input=json.dumps({'apiVersion':'v1','kind':'Namespace','metadata':{'name':ns}}))
 secretname=next(e['valueFrom']['secretKeyRef']['name'] for e in d['spec']['template']['spec']['containers'][0]['env'] if e['name']=='DB_PASSWORD')
 s=json.loads(k('-n','payflow','get','secret',secretname,'-o','json'))
 k('apply','-f','-',input=json.dumps({'apiVersion':'v1','kind':'Secret','metadata':{'name':'payflow-secrets','namespace':ns},'type':'Opaque','data':{key:s['data'][key] for key in ['DB_PASSWORD','API_KEY_PEPPER','PROVIDER_A_WEBHOOK_SECRET','PROVIDER_B_WEBHOOK_SECRET']}}))
 k('apply','-k',str(root/'performance/k8s'));k('-n',ns,'rollout','status','deployment/payflow','--timeout=300s')
 print('Isolated performance environment ready. Seed through the API next.')
else:
 state=json.loads((out/'environment-state.json').read_text())
 # Refuse to leave durable work behind or silently switch database consumers while unfinished.
 pending=k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',"SELECT count(*) FROM outbox_events WHERE status<>'PUBLISHED';").strip()
 if pending!='0':sys.exit('Performance outbox must drain before restoration. Pending/failed count: '+pending)
 unfinished=k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',"SELECT (SELECT count(*) FROM payments WHERE status IN ('CREATED','PROCESSING','PENDING_CONFIRMATION')) + (SELECT count(*) FROM refunds WHERE status IN ('CREATED','PROCESSING','PENDING_CONFIRMATION'));").strip()
 if unfinished!='0':sys.exit('Performance payments/refunds must reach terminal states before switching consumers: '+unfinished)
 k('-n',ns,'delete','hpa','payflow','--ignore-not-found');k('-n',ns,'scale','deployment','payflow','--replicas=0')
 k('-n',ns,'wait','--for=delete','pod','-l','app.kubernetes.io/name=payflow,app.kubernetes.io/component=api','--timeout=180s')
 k('-n',ns,'delete','pod','payflow-k6','--ignore-not-found');k('-n',ns,'delete','secret','payflow-performance-key','--ignore-not-found')
 k('-n','payflow','patch','statefulset','postgres','--type=strategic','-p',json.dumps({'spec':{'template':{'spec':{'containers':[{'name':'postgres','args':state['postgres_args']}]}}}}))
 k('-n','payflow','rollout','status','statefulset/postgres','--timeout=180s')
 k('apply','-f','-',input=json.dumps(state['hpa']));k('-n','payflow','scale','deployment','payflow','--replicas='+str(state['replicas']))
 k('-n','payflow','rollout','status','deployment/payflow','--timeout=300s')
 (out/'environment-state.json').rename(out/'environment-restored.json');print('Normal local deployment restored; performance data and reports retained.')
