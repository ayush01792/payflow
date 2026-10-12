#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
namespace=${PAYFLOW_NAMESPACE:-payflow}
kubectl -n "$namespace" rollout status deployment/payflow --timeout=600s
kubectl -n "$namespace" get pods -l app.kubernetes.io/name=payflow,app.kubernetes.io/component=api
kubectl -n "$namespace" exec deployment/payflow -c payflow -- curl --fail --silent --max-time 10 http://127.0.0.1:9091/actuator/health/readiness
printf '\n'
api=${PAYFLOW_URL:-http://127.0.0.1:8080}
curl --fail --silent --max-time 10 "$api/api/v1/health"
printf '\n'
if [[ -n "${PAYFLOW_API_KEY_FILE:-}" ]]; then
  umask 077
  headers=$(mktemp)
  trap 'rm -f "$headers"' EXIT
  printf 'X-API-Key: ' > "$headers"
  cat "$PAYFLOW_API_KEY_FILE" >> "$headers"
  command -v python3 >/dev/null || { echo "Optional payment check needs Python 3" >&2; exit 1; }
  payment=$(curl --fail --silent --show-error --max-time 15 -H @"$headers" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: k8s-smoke-$(date +%s)-$$" \
    -d '{"userId":"k8s-smoke-user","amount":10,"currency":"USD","paymentMethod":"UPI"}' "$api/api/v1/payments")
  id=$(printf '%s' "$payment" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
  for ((attempt=1; attempt<=60; attempt++)); do
    payment=$(curl --fail --silent --show-error --max-time 10 -H @"$headers" "$api/api/v1/payments/$id")
    status=$(printf '%s' "$payment" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')
    if [[ "$status" == SUCCESS ]]; then echo "Demo payment $id reached SUCCESS"; exit 0; fi
    if [[ "$status" == FAILED ]]; then echo "Demo payment failed" >&2; exit 1; fi
    sleep 2
  done
  echo "Demo payment did not reach SUCCESS" >&2; exit 1
fi
