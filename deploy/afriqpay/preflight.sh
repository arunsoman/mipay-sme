#!/bin/sh
# Run before `docker compose up` of the mipay stack. The backend mounts the restricted
# trustplane volume `trustplane_backend-pki` (external); it only exists once the trustplane
# stack's pki-init has run at a commit that has deploy/pki/export-backend.sh (7da9ae9+).
# Order: trustplane first (pki-init re-exports the backend material), then mipay.
set -eu

VOL=trustplane_backend-pki
if ! docker volume inspect "$VOL" >/dev/null 2>&1; then
  echo "[preflight] volume $VOL is missing: deploy trustplane first (its pki-init exports it), e.g." >&2
  echo "  docker compose -f ../../trustplane/docker-compose.yml -f trustplane.override.yml up -d pki-init" >&2
  exit 1
fi
missing=$(docker run --rm -v "$VOL":/v:ro alpine:3.20 sh -c \
  'for f in mipay-mesh.pem mipay-mesh-key.pem platform-ca.pem ctx-secret; do [ -s /v/$f ] || echo $f; done')
if [ -n "$missing" ]; then
  echo "[preflight] $VOL lacks: $missing - rerun trustplane pki-init" >&2
  exit 1
fi
echo "[preflight] $VOL ok"
