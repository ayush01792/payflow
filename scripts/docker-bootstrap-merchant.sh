#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
merchant=${1:-local-demo-merchant}
output=${2:-secrets/local-merchant.api-key}
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
  if [[ -n "$remote_file" ]]; then docker compose exec -T payflow rm -f -- "$remote_file" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
# Existing non-web bootstrap profile: no new credential HTTP endpoint or secret logging.
result=$(docker compose exec -T -e JAVA_TOOL_OPTIONS="-Xms32m -Xmx256m" payflow java -jar /app/payflow.jar \
  --spring.profiles.active=bootstrap \
  --payflow.bootstrap.action=create-key \
  --payflow.bootstrap.external-id="$merchant" \
  --payflow.bootstrap.merchant-name="Local Compose merchant" \
  --payflow.bootstrap.key-name="Local Compose client")
remote_file=$(printf '%s\n' "$result" | sed -n 's/.*one-time key file: \(\/tmp\/payflow-credential-[A-Za-z0-9._-]*\.api-key\)$/\1/p' | tail -1)
if [[ -z "$remote_file" ]]; then echo "Bootstrap did not return a secure key-file path" >&2; exit 1; fi
docker compose exec -T payflow cat -- "$remote_file" > "$local_file"
[[ -s "$local_file" ]] || { echo "Empty bootstrap credential" >&2; exit 1; }
chmod 600 "$local_file"
# A hard link atomically refuses overwrite, including a concurrent bootstrap script.
ln "$local_file" "$output"
echo "Merchant credential saved to owner-only file: $output"
