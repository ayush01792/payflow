#!/usr/bin/env python3
"""Run pinned k6 against the ClusterIP, with an owner-only key file mounted as a Secret."""
import argparse,json,os,subprocess,time,pathlib,sys,re
ROOT=pathlib.Path(__file__).resolve().parents[2];NS=os.environ.get('PAYFLOW_NAMESPACE','payflow-performance')
def kub(*args,input=None):
 return subprocess.run(['kubectl','-n',NS,*args],input=input,text=True,capture_output=True,check=True).stdout
p=argparse.ArgumentParser();p.add_argument('script');p.add_argument('label');p.add_argument('settings',nargs='*');a=p.parse_args()
if a.script not in {x.name for x in (ROOT/'performance/k6').glob('*.js')}:p.error('Unknown script')
if not re.fullmatch('[A-Za-z0-9_-]+',a.label):p.error('Invalid result label')
keyfile=pathlib.Path(os.environ.get('API_KEY_FILE',ROOT/'secrets/performance.api-key'))
fixturefile=pathlib.Path(os.environ.get('FIXTURES_FILE',ROOT/'performance/results/fixtures.json'))
secret=kub('create','secret','generic','payflow-performance-key','--from-file=api-key='+str(keyfile),'--dry-run=client','-o','json');kub('apply','-f','-',input=secret)
files={f.name:f.read_text() for f in (ROOT/'performance/k6').glob('*.js')};files['fixtures.json']=fixturefile.read_text() if fixturefile.exists() else '{"payments":[],"refunds":[]}'
kub('apply','-f','-',input=json.dumps({'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':'payflow-k6-scripts'},'data':files}))
kub('delete','pod','payflow-k6','--ignore-not-found','--wait=true')
pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':'payflow-k6'},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,'securityContext':{'runAsNonRoot':True,'runAsUser':12345,'runAsGroup':12345,'fsGroup':12345,'seccompProfile':{'type':'RuntimeDefault'}},'containers':[{'name':'k6','image':'grafana/k6:2.3.0','command':['sh','-c','sleep 86400'],'resources':{'requests':{'cpu':'100m','memory':'64Mi'},'limits':{'cpu':'1','memory':'256Mi'}},'securityContext':{'allowPrivilegeEscalation':False,'readOnlyRootFilesystem':True,'capabilities':{'drop':['ALL']}},'volumeMounts':[{'name':'scripts','mountPath':'/scripts','readOnly':True},{'name':'key','mountPath':'/credential','readOnly':True},{'name':'tmp','mountPath':'/tmp'}]}],'volumes':[{'name':'scripts','configMap':{'name':'payflow-k6-scripts'}},{'name':'key','secret':{'secretName':'payflow-performance-key','defaultMode':288}},{'name':'tmp','emptyDir':{'sizeLimit':'64Mi'}}]}}
kub('apply','-f','-',input=json.dumps(pod));kub('wait','--for=condition=Ready','pod/payflow-k6','--timeout=180s')
env=['BASE_URL=http://payflow','API_KEY_FILE=/credential/api-key','FIXTURES_FILE=/scripts/fixtures.json','RUN_ID=loadtest-'+a.label+'-'+str(int(time.time()*1000))]
for setting in a.settings:
 if '=' not in setting or setting.split('=')[0] in {'API_KEY','API_KEY_FILE','FIXTURES_FILE','RUN_ID'}:p.error('Invalid or sensitive setting')
 env.append(setting)
out=ROOT/'performance/results';out.mkdir(exist_ok=True);start=time.time()
(out/(a.label+'-start.json')).write_text(json.dumps({'script':a.script,'settings':a.settings,'start':start},indent=2))
proc=subprocess.run(['kubectl','-n',NS,'exec','payflow-k6','--','env',*env,'k6','run','--quiet','--summary-mode=full','/scripts/'+a.script],text=True,capture_output=True)
(out/(a.label+'.log')).write_text(proc.stdout+proc.stderr)
for line in proc.stdout.splitlines():
 if line.startswith('PAYFLOW_SUMMARY='):(out/(a.label+'.json')).write_text(line.split('=',1)[1])
(out/(a.label+'-run.json')).write_text(json.dumps({'script':a.script,'settings':a.settings,'start':start,'end':time.time(),'exit_code':proc.returncode},indent=2))
print(a.label+': exit='+str(proc.returncode)+' elapsed='+str(round(time.time()-start,1))+'s',flush=True)
if a.script=='seed.js':
 ids=re.findall(r'PAYFLOW_FIXTURE=([a-f0-9-]{36})',proc.stderr+proc.stdout);(out/'fixtures.json').write_text(json.dumps({'payments':ids,'refunds':ids[-min(20,len(ids)):]}));print('Seed fixtures:',len(ids),flush=True)
sys.exit(proc.returncode)
