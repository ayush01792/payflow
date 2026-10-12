#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
namespace=${PAYFLOW_NAMESPACE:-payflow}
pod=$(kubectl -n "$namespace" get pods -l app.kubernetes.io/name=payflow,app.kubernetes.io/component=api --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
[[ -n "$pod" ]] || { echo "No running PayFlow Pod found" >&2; exit 1; }
merchant=${1:-kubernetes-demo-merchant}
output=${2:-secrets/kubernetes-merchant.api-key}
if [[ -e "$output" ]]; then
  echo "Refusing to overwrite an existing key file: $output" >&2
  exit 1
fi
umask 077
mkdir -p "$(dirname "$output")"
local_file=$(mktemp "$(dirname "$output")/.payflow-key.XXXXXX")
remote_file=
cleanup() {
  rm -f "$local_file"
  if [[ -n "$remote_file" ]]; then kubectl -n "$namespace" exec "$pod" -c payflow -- rm -f -- "$remote_file" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
# Existing non-web bootstrap profile: no new credential HTTP endpoint or secret logging.
result=$(kubectl -n "$namespace" exec "$pod" -c payflow -- env JAVA_TOOL_OPTIONS="-Xms32m -Xmx256m" java -jar /app/payflow.jar \
  --spring.profiles.active=bootstrap \
  --payflow.bootstrap.action=create-key \
  --payflow.bootstrap.external-id="$merchant" \
  --payflow.bootstrap.merchant-name="Local Kubernetes merchant" \
  --payflow.bootstrap.key-name="Local Kubernetes client")
remote_file=$(printf '%s\n' "$result" | sed -n 's/.*one-time key file: \(\/tmp\/payflow-credential-[A-Za-z0-9._-]*\.api-key\)$/\1/p' | tail -1)
if [[ -z "$remote_file" ]]; then echo "Bootstrap did not return a secure key-file path" >&2; exit 1; fi
kubectl -n "$namespace" exec "$pod" -c payflow -- cat -- "$remote_file" > "$local_file"
[[ -s "$local_file" ]] || { echo "Empty bootstrap credential" >&2; exit 1; }
chmod 600 "$local_file"
# A hard link atomically refuses overwrite, including a concurrent bootstrap script.
ln "$local_file" "$output"
echo "Merchant credential saved to owner-only file: $output"
