#!/usr/bin/env python3
"""Bounded snapshots, not per-request SQL logging. No credentials included."""
import subprocess,pathlib,json,sys,time,os
root=pathlib.Path(__file__).resolve().parents[2];out=root/'performance/results';out.mkdir(exist_ok=True);ns=os.environ.get('PAYFLOW_NAMESPACE','payflow-performance')
def k(*args,namespace=ns):
 p=subprocess.run(['kubectl','-n',namespace,*args],capture_output=True,text=True,timeout=35);return p.stdout if p.returncode==0 else 'ERROR: '+p.stderr
label=sys.argv[1];data={'timestamp':time.time(),'pods':k('get','pods','-o','json'),'top':k('top','pods'),'hpa':k('get','hpa','-o','json')}
data['postgres']=k('exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',"SELECT relname,n_tup_ins,n_tup_upd,n_dead_tup FROM pg_stat_user_tables WHERE relname IN ('merchant_api_keys','payments','refunds','outbox_events'); SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY 1,2,3; SELECT status,count(*) FROM outbox_events GROUP BY status; SELECT max(EXTRACT(epoch FROM now()-created_at)) FROM outbox_events WHERE status<>'PUBLISHED'; SELECT status,count(*) FROM payments GROUP BY status; SELECT query,calls,total_exec_time,mean_exec_time,rows FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) ORDER BY total_exec_time DESC LIMIT 15;",namespace='payflow')
data['request_queries']=k('exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',"SELECT query,calls,total_exec_time,rows FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) AND (query LIKE 'select p1_0.%' OR query LIKE 'SELECT k.id,%' OR query LIKE 'UPDATE merchant_api_keys%');",namespace='payflow')
data['redis']=k('exec','deployment/redis','--','redis-cli','INFO','stats',namespace='payflow')+'\n'+k('exec','deployment/redis','--','redis-cli','INFO','clients',namespace='payflow')
data['infrastructure_top']=k('top','pods',namespace='payflow')
data['postgres_cpu_stat']=k('exec','postgres-0','--','cat','/sys/fs/cgroup/cpu.stat',namespace='payflow')
data['app']={}
for pod in json.loads(data['pods'])['items']:
 name=pod['metadata']['name']
 if name.startswith('payflow-') and name!='payflow-k6' and pod['status']['phase']=='Running':
  data['app'][name]={'metrics':k('exec',name,'-c','payflow','--','curl','-s','--max-time','8','http://localhost:9091/actuator/prometheus'),'cpu_stat':k('exec',name,'-c','payflow','--','cat','/sys/fs/cgroup/cpu.stat'),'memory':k('exec',name,'-c','payflow','--','cat','/sys/fs/cgroup/memory.current')}
(out/(label+'-snapshot.json')).write_text(json.dumps(data,indent=2));print(label+': snapshot saved',flush=True)
