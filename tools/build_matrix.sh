#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

targets=(
  1.20.1-fabric
  1.20.1-forge
  1.21.1
  26.1.2
  26.2
)

for target in "${targets[@]}"; do
  printf 'Building updater target %s\n' "$target"
  "$PROJECT_DIR/gradlew" build "-Pupdater_target=$target"
done
