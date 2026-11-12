#!/bin/sh
# Export only the MiPay backend's required trust material from the full PKI volume.
set -eu

SOURCE=/pki
TARGET=/backend-pki
mkdir -p "$TARGET"
cp "$SOURCE/mipay-mesh.pem" "$SOURCE/mipay-mesh-key.pem" "$SOURCE/platform-ca.pem" "$SOURCE/ctx-secret" "$TARGET/"
chmod 644 "$TARGET/mipay-mesh.pem" "$TARGET/platform-ca.pem"
chmod 600 "$TARGET/mipay-mesh-key.pem" "$TARGET/ctx-secret"
echo "[pki] exported restricted backend trust material"
