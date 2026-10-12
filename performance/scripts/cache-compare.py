#!/usr/bin/env python3
"""Identical finite reads: each merchant-owned fixture exactly once cold, then warm."""
import json,pathlib,subprocess,os
r=pathlib.Path(__file__).resolve().parents[2]
if os.environ.get('PAYFLOW_NAMESPACE','payflow-performance')!='payflow-performance':raise SystemExit('Use the isolated performance namespace')
ids=json.loads((r/'performance/results/fixtures.json').read_text())['payments']
for start in range(0,len(ids),50):
 subprocess.run(['kubectl','-n','payflow','exec','deployment/redis','--','redis-cli','-n','1','DEL',*['payflow:payment:'+p for p in ids[start:start+50]]],capture_output=True,check=True)
for label in ['cold-cache','warm-cache']:
 subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label+'-before'],check=True)
 subprocess.run(['python3',str(r/'performance/scripts/run-kubernetes.py'),'cache-read.js',label,'VUS=4'],check=True)
 subprocess.run(['python3',str(r/'performance/scripts/collect.py'),label+'-after'],check=True)
