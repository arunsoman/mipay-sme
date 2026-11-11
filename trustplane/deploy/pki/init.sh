#!/bin/sh
# Generate the full PKI for the trust plane (idempotent: skips existing files).
set -eu
PKI=/pki
mkdir -p "$PKI/signing" "$PKI/clients"

have() { [ -f "$1" ]; }

# ---- platform CA (signs server + mesh certs) -------------------------------
if [ ! -f "$PKI/platform-ca.pem" ]; then
  openssl req -x509 -newkey rsa:3072 -nodes -days 3650 \
    -keyout "$PKI/platform-ca-key.pem" -out "$PKI/platform-ca.pem" \
    -subj "/CN=SME Trust Plane CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

# ---- client CA (partner client certificates) -------------------------------
if [ ! -f "$PKI/client-ca.pem" ]; then
  openssl req -x509 -newkey rsa:3072 -nodes -days 3650 \
    -keyout "$PKI/client-ca-key.pem" -out "$PKI/client-ca.pem" \
    -subj "/CN=SME Partner Client CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

# ---- server + mesh certificates --------------------------------------------
sign_server() { # name, cn, san...
  name="$1"; cn="$2"; san="$3"
  if [ -f "$PKI/$name-server.pem" ]; then return 0; fi
  openssl req -newkey rsa:2048 -nodes -keyout "$PKI/$name-server-key.pem" \
    -out "/tmp/$name.csr" -subj "/CN=$cn"
  printf "subjectAltName=%s\nextendedKeyUsage=serverAuth,clientAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n" "$san" > "/tmp/$name.ext"
  openssl x509 -req -in "/tmp/$name.csr" -CA "$PKI/platform-ca.pem" \
    -CAkey "$PKI/platform-ca-key.pem" -CAcreateserial -days 825 \
    -extfile "/tmp/$name.ext" -out "$PKI/$name-server.pem"
}

sign_server gateway "sme-gateway" "DNS:gateway,DNS:localhost,DNS:idp.fapi.local,DNS:api.fapi.local,DNS:rbac.fapi.local"
sign_server idp    "sme-idp"    "DNS:idp"
sign_server rbac   "sme-rbac"   "DNS:rbac"

# gateway mesh CLIENT cert (used for upstream mTLS)
if [ ! -f "$PKI/gateway-mesh.pem" ]; then
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
    -keyout "$PKI/gateway-mesh-key.pem" -out /tmp/mesh.csr -subj "/CN=sme-gateway-mesh"
  printf "extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature\n" > /tmp/mesh.ext
  openssl x509 -req -in /tmp/mesh.csr -CA "$PKI/platform-ca.pem" \
    -CAkey "$PKI/platform-ca-key.pem" -CAcreateserial -days 825 \
    -extfile /tmp/mesh.ext -out "$PKI/gateway-mesh.pem"
fi

# mipay backend mesh CLIENT cert (mTLS to RBAC's /rbac/internal/* registry).
# CN sme-mipay-mesh normalizes to "mipay", the X-Mesh-Service the backend sends.
if [ ! -f "$PKI/mipay-mesh.pem" ]; then
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
    -keyout "$PKI/mipay-mesh-key.pem" -out /tmp/mipay-mesh.csr -subj "/CN=sme-mipay-mesh"
  printf "extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature\n" > /tmp/mipay-mesh.ext
  openssl x509 -req -in /tmp/mipay-mesh.csr -CA "$PKI/platform-ca.pem" \
    -CAkey "$PKI/platform-ca-key.pem" -CAcreateserial -days 825 \
    -extfile /tmp/mipay-mesh.ext -out "$PKI/mipay-mesh.pem"
fi

# ---- gateway-bff OAuth client key (private_key_jwt) -------------------------
if [ ! -f "$PKI/clients/gateway-bff.pem" ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$PKI/clients/gateway-bff.pem"
  chmod 600 "$PKI/clients/gateway-bff.pem"
fi

# ---- IdP signing keys (PS256 / ES256 / EdDSA) -------------------------------
if [ ! -f "$PKI/signing/ps256.pem" ]; then
  openssl genrsa -out "$PKI/signing/ps256.pem" 2048
fi
if [ ! -f "$PKI/signing/es256.pem" ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$PKI/signing/es256.pem"
fi
if [ ! -f "$PKI/signing/eddsa.pem" ]; then
  openssl genpkey -algorithm ED25519 -out "$PKI/signing/eddsa.pem"
fi

# ---- client keys & certificates --------------------------------------------
# web client signing key (private_key_jwt assertions)
if [ ! -f "$PKI/clients/web-es256.pem" ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$PKI/clients/web-es256.pem"
fi
# gateway introspection client (private_key_jwt)
if [ ! -f "$PKI/clients/gw-introspect.pem" ]; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$PKI/clients/gw-introspect.pem"
fi

# partner TLS client certs (tls_client_auth)
partner_cert() { # name, cn
  name="$1"; cn="$2"
  if [ -f "$PKI/clients/$name.pem" ]; then return 0; fi
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
    -keyout "$PKI/clients/$name-key.pem" -out "/tmp/$name.csr" -subj "/CN=$cn"
  printf "extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature\n" > "/tmp/$name.ext"
  openssl x509 -req -in "/tmp/$name.csr" -CA "$PKI/client-ca.pem" \
    -CAkey "$PKI/client-ca-key.pem" -CAcreateserial -days 825 \
    -extfile "/tmp/$name.ext" -out "$PKI/clients/$name.pem"
}
partner_cert cert-a "partner-cli-a"
partner_cert cert-b "partner-cli-b"

# ---- gateway context HMAC secret -------------------------------------------
if [ ! -f "$PKI/ctx-secret" ]; then
  openssl rand -hex 32 > "$PKI/ctx-secret"
fi


# service containers run as uid 10001: they must be able to read their keys
chown -R 10001:10001 "$PKI" 2>/dev/null || true
chmod 640 "$PKI"/*-key.pem "$PKI"/signing/*.pem "$PKI"/clients/*.pem "$PKI"/ctx-secret 2>/dev/null || true
chmod 755 "$PKI" "$PKI/signing" "$PKI/clients"

echo "[pki] ready:"
ls -1 "$PKI"
exit 0
