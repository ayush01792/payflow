#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${KUBECONFIG:?Set KUBECONFIG for a disposable local test cluster}"
export PAYFLOW_NAMESPACE=${PAYFLOW_NAMESPACE:-payflow-performance}
export API_KEY_FILE=${API_KEY_FILE:-"$PWD/secrets/performance.api-key"}
if [[ ! -f "$API_KEY_FILE" ]]; then bash scripts/k8s-bootstrap-merchant.sh "${PERFORMANCE_MERCHANT:-loadtest-performance}" "$API_KEY_FILE"; fi
python3 performance/scripts/run-kubernetes.py seed.js seed "SEED_COUNT=${SEED_COUNT:-200}"
