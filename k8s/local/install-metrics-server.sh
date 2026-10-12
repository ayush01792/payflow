#!/usr/bin/env bash
set -euo pipefail
[[ $(kubectl config current-context) == kind-payflow ]] || { echo "Select the dedicated kind-payflow context first" >&2; exit 1; }
# Cluster tooling, intentionally separate from the PayFlow base/local application.
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.8.1/components.yaml
# Kind kubelet certificates lack the SAN needed for normal Metrics Server validation.
# This exception is LOCAL ONLY; real clusters must supply valid kubelet certificates.
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
kubectl -n kube-system rollout status deployment/metrics-server --timeout=360s
