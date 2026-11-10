#!/usr/bin/env bash
# KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
#
# TEST-ONLY certificates for the local TLS relay's unit tests (LocalRelayTest, RelayTrustTest).
# Nothing here is a secret and nothing here ships in the app: the files land in
# app/src/test/resources/relay/, which only the JVM unit tests read.
#
# Regenerate (overwrites every file in that folder; commit the result):
#   bash tools/kevbox/relay/make-test-certs.sh
#
# What it writes, each valid for 36,500 days:
#   test-root-ca.pem      the test root CA; the tests' base trust holds this root alone
#   relay-test.p12        key + leaf for relay-test.invalid + the intermediate, signed by the
#                         intermediate (the root signs the intermediate, as Let's Encrypt chains do)
#   wrong-name.p12        the same chain shape, but the leaf names wrong.invalid
#   other-ca.p12          key + leaf for relay-test.invalid signed by a second CA the base trust
#                         never includes
#   other-ca.pem          that second CA, for the debug-only extra roots test
# Each .p12 has the password "test". The CA keys are made in a temporary folder and thrown away.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="$ROOT/app/src/test/resources/relay"
DAYS=36500
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }

ca_ext() {
  printf 'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n' > "$1"
}

leaf_ext() {
  printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:%s\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n' "$2" > "$1"
}

# self_ca <name> <cn>: a self-signed root.
self_ca() {
  key "$WORK/$1.key"
  openssl req -new -x509 -key "$WORK/$1.key" -out "$WORK/$1.pem" -days "$DAYS" -subj "/CN=$2" \
    -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign'
}

# signed <name> <cn> <issuer> <extfile>
signed() {
  key "$WORK/$1.key"
  openssl req -new -key "$WORK/$1.key" -out "$WORK/$1.csr" -subj "/CN=$2"
  openssl x509 -req -in "$WORK/$1.csr" -CA "$WORK/$3.pem" -CAkey "$WORK/$3.key" -CAcreateserial \
    -out "$WORK/$1.pem" -days "$DAYS" -extfile "$4" 2>/dev/null
}

# p12 <name> <out> [chain pem]: key + leaf (+ chain) as PKCS#12, password "test".
p12() {
  local chain=()
  [ $# -ge 3 ] && chain=(-certfile "$WORK/$3.pem")
  openssl pkcs12 -export -inkey "$WORK/$1.key" -in "$WORK/$1.pem" "${chain[@]}" \
    -name server -passout pass:test -out "$OUT/$2"
  # The JVM must be able to read it, or the tests would fail for the wrong reason.
  keytool -list -keystore "$OUT/$2" -storetype PKCS12 -storepass test >/dev/null
}

ca_ext "$WORK/ca.ext"
leaf_ext "$WORK/relay.ext" relay-test.invalid
leaf_ext "$WORK/wrong.ext" wrong.invalid

self_ca root "KevBox Relay Test Root CA"
signed inter "KevBox Relay Test Intermediate CA" root "$WORK/ca.ext"
signed relay relay-test.invalid inter "$WORK/relay.ext"
signed wrong wrong.invalid inter "$WORK/wrong.ext"

self_ca other "KevBox Relay Test Other CA"
signed otherleaf relay-test.invalid other "$WORK/relay.ext"

cp "$WORK/root.pem" "$OUT/test-root-ca.pem"
cp "$WORK/other.pem" "$OUT/other-ca.pem"
p12 relay relay-test.p12 inter
p12 wrong wrong-name.p12 inter
p12 otherleaf other-ca.p12

echo "wrote test-only relay certificates to $OUT"
