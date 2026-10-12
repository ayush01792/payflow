#!/usr/bin/env python3
"""Print only measured aggregates; empty metrics stay unavailable."""
import json,pathlib
root=pathlib.Path(__file__).resolve().parents[1]/'results'
print('| Run | HTTP RPS | HTTP p50 ms | p95 ms | p99 ms | HTTP errors % | Dropped iterations |')
print('|---|---:|---:|---:|---:|---:|---:|')
for p in sorted(root.glob('*.json')):
 try:d=json.loads(p.read_text())
 except json.JSONDecodeError:continue
 m=d.get('metrics')
 if not m:continue
 def val(n,k):return m.get(n,{}).get('values',{}).get(k)
 def fmt(v):return '—' if v is None else f'{v:.2f}'
 e=val('http_req_failed','rate');print('| '+p.stem+' | '+' | '.join(fmt(v) for v in [val('http_reqs','rate'),val('http_req_duration','med'),val('http_req_duration','p(95)'),val('http_req_duration','p(99)'),e*100 if e is not None else None,val('dropped_iterations','count') or 0])+' |')
