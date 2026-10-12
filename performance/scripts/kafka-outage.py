#!/usr/bin/env python3
"""Brief approved local Kafka outage with moderate traffic; always restore the broker."""
import subprocess,os,pathlib,time,json
root=pathlib.Path(__file__).resolve().parents[2]
def k(*args):return subprocess.run(['kubectl',*args],capture_output=True,text=True,check=True).stdout
if k('config','current-context').strip()!='kind-payflow':raise SystemExit('Local kind-payflow only')
if os.environ.get('PAYFLOW_NAMESPACE','payflow-performance')!='payflow-performance':raise SystemExit('Use the performance namespace')
label='kafka-outage';out=root/'performance/results';startfile=out/(label+'-start.json');startfile.unlink(missing_ok=True)
def sql(q):return k('-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',q).strip()
def sample():return {'time':time.time(),'outbox':sql("SELECT count(*),coalesce(max(EXTRACT(epoch FROM clock_timestamp()-created_at)),0) FROM outbox_events WHERE status IN ('PENDING','CLAIMED');"),'payments':sql("SELECT status,count(*) FROM payments WHERE user_id LIKE 'loadtest-kafka-outage-%' GROUP BY status;")}
events={'samples':[]}
load=subprocess.Popen(['python3',str(root/'performance/scripts/run-kubernetes.py'),'payment-create.js',label,'RATE=2','DURATION=90s'],env=os.environ.copy())
try:
 deadline=time.time()+180
 while not startfile.exists():
  if load.poll() is not None or time.time()>deadline:raise RuntimeError('Load did not start')
  time.sleep(1)
 events['traffic_start']=json.loads(startfile.read_text())['start'];events['samples'].append(sample())
 time.sleep(8);events['stop_command']=time.time();k('-n','payflow','scale','statefulset','kafka','--replicas=0')
 k('-n','payflow','wait','--for=delete','pod/kafka-0','--timeout=90s');events['broker_absent']=time.time()
 for i in range(4):events['samples'].append(sample());time.sleep(5)
finally:
 events['restore_command']=time.time();k('-n','payflow','scale','statefulset','kafka','--replicas=1')
 k('-n','payflow','rollout','status','statefulset/kafka','--timeout=180s');events['broker_ready']=time.time()
 code=load.wait(timeout=150);events['load_exit']=code
 for i in range(48):
  s=sample();events['samples'].append(s)
  if s['outbox'].split('|')[0]=='0' and not any(x in s['payments'] for x in ['CREATED','PROCESSING','PENDING_CONFIRMATION']):events['drained']=time.time();break
  time.sleep(5)
 (out/'kafka-outage-events.json').write_text(json.dumps(events,indent=2))
 print('Kafka restored; recovery evidence saved.',flush=True)
 if 'drained' not in events:raise RuntimeError('Backlog did not drain within bounded recovery window')
