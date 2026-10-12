#!/usr/bin/env bash
set -euo pipefail
cluster=${1:-payflow}
kind_bin=${KIND_BIN:-kind}
images=(payflow:local payflow-otel-collector:local)
# Kubelet pulls pinned official dependency images directly, avoiding duplicate caches.
for image in "${images[@]}"; do docker image inspect "$image" >/dev/null; done
if "$kind_bin" load docker-image "${images[@]}" --name "$cluster"; then exit 0; fi
# Docker Desktop's containerd store can retain an index with undownloaded platforms.
# Export only the locally built app architecture; do not require a registry push.
architecture=$(docker image inspect payflow:local --format '{{.Architecture}}')
echo "Retrying Kind import with linux/$architecture image content"
# Avoid a large temporary host archive on constrained machines.
for node in $("$kind_bin" get nodes --name "$cluster"); do
  docker image save --platform="linux/$architecture" "${images[@]}" | \
    docker exec -i "$node" ctr --namespace=k8s.io images import --platform="linux/$architecture" -
done
