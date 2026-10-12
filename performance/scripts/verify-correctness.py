#!/usr/bin/env python3
"""Read-only checks over synthetic performance data after traffic/recovery drains."""
import subprocess,json,pathlib
root=pathlib.Path(__file__).resolve().parents[2]
queries={
 'duplicate_merchant_idempotency_keys':"SELECT count(*) FROM (SELECT merchant_id,idempotency_key FROM payment_idempotency GROUP BY 1,2 HAVING count(*)>1) s",
 'duplicate_synthetic_user_ids':"SELECT count(*) FROM (SELECT merchant_id,user_id FROM payments WHERE user_id LIKE 'loadtest-%' AND user_id NOT LIKE 'loadtest-idempotency-%' GROUP BY 1,2 HAVING count(*)>1) s",
 'over_reserved_refunds':"SELECT count(*) FROM (SELECT p.id FROM payments p JOIN refunds r ON r.payment_id=p.id WHERE r.status<>'FAILED' GROUP BY p.id,p.amount HAVING sum(r.amount)>p.amount) s",
 'unpublished_outbox':"SELECT count(*) FROM outbox_events WHERE status<>'PUBLISHED'",
 'unprocessed_published_events':"SELECT count(*) FROM outbox_events o WHERE status='PUBLISHED' AND NOT EXISTS(SELECT 1 FROM processed_kafka_events e WHERE e.event_id=o.id) AND NOT EXISTS(SELECT 1 FROM processed_refund_events e WHERE e.event_id=o.id)",
 'duplicate_provider_charge_records':"SELECT count(*) FROM (SELECT payment_id FROM mock_provider_payments WHERE result->>'outcome'='SUCCESS' GROUP BY 1 HAVING count(*)>1) s",
 'nonterminal_payments':"SELECT count(*) FROM payments WHERE status IN ('CREATED','PROCESSING','PENDING_CONFIRMATION')",
 'nonterminal_refunds':"SELECT count(*) FROM refunds WHERE status IN ('CREATED','PROCESSING','PENDING_CONFIRMATION')",
}
def sql(q):return subprocess.run(['kubectl','-n','payflow','exec','postgres-0','--','psql','-U','payflow','-d','payflow_performance','-Atc',q],capture_output=True,text=True,check=True).stdout.strip()
results={name:int(sql(q)) for name,q in queries.items()}
results['provider_calls_by_workload']=sql("SELECT substring(p.user_id from '^loadtest-(provider-[a-z]+|kafka-outage)-'),count(DISTINCT p.id),count(m.id),coalesce(sum(m.call_count),0),max(m.call_count) FROM payments p LEFT JOIN mock_provider_payments m ON m.payment_id=p.id WHERE p.user_id LIKE 'loadtest-provider-%' OR p.user_id LIKE 'loadtest-kafka-outage-%' GROUP BY 1 ORDER BY 1")
(root/'performance/results/correctness.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2))
if any(v for v in results.values() if isinstance(v,int)):raise SystemExit('Correctness checks have nonzero findings; investigate before restoring')
