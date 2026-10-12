#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
label=${1:?Provide result label}
[[ "$label" =~ ^[A-Za-z0-9_-]+$ ]] || exit 1
python3 performance/scripts/collect.py "$label"
