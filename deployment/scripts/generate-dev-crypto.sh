#!/usr/bin/env bash
# Генератор mTLS-сертификатов и ES256 JWT-ключей для одного dev VPS.
# Запускать на VPS: sudo bash deployment/scripts/generate-dev-crypto.sh
set -Eeuo pipefail
umask 077

CRYPTO_DIR="/opt/fitness-platform/crypto"
FORCE=false

if [[ "${1:-}" == "--force" ]]; then
  FORCE=true
elif [[ $# -ne 0 ]]; then
  echo "Использование: $0 [--force]" >&2
  exit 2
fi

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Запустите скрипт через sudo: sudo bash $0" >&2
  exit 1
fi

command -v openssl >/dev/null || { echo "Не найден openssl" >&2; exit 1; }

if [[ -e "$CRYPTO_DIR/ca.key" && "$FORCE" != true ]]; then
  echo "Crypto-файлы уже существуют: $CRYPTO_DIR" >&2
  echo "Ничего не перезаписано. Для полной ручной замены используйте --force." >&2
  exit 1
fi

install -d -m 0750 "$CRYPTO_DIR"

if [[ "$FORCE" == true ]]; then
  rm -f "$CRYPTO_DIR"/{ca.crt,ca.key,identity-server.crt,identity-server-pkcs8.key,identity-client.crt,identity-client-pkcs8.key,gateway-client.crt,gateway-client-pkcs8.key,accounts-server.crt,accounts-server-pkcs8.key,coins-server.crt,coins-server-pkcs8.key,identity-jwt-private.pem,identity-jwt-public.pem}
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
SERIAL_FILE="$TEMP_DIR/ca.srl"

create_leaf() {
  local name="$1"
  local dns_name="$2"
  local uri_name="$3"
  local usage="$4"
  local key="$CRYPTO_DIR/${name}-pkcs8.key"
  local cert="$CRYPTO_DIR/${name}.crt"
  local csr="$TEMP_DIR/${name}.csr"
  local extension="$TEMP_DIR/${name}.ext"

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$key"
  openssl req -new -key "$key" -out "$csr" -subj "/CN=${dns_name}"
  cat > "$extension" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=${usage}
subjectAltName=DNS:${dns_name},URI:${uri_name}
EOF
  if [[ -e "$SERIAL_FILE" ]]; then
    openssl x509 -req -in "$csr" -CA "$CRYPTO_DIR/ca.crt" -CAkey "$CRYPTO_DIR/ca.key" \
      -CAserial "$SERIAL_FILE" -out "$cert" -days 90 -sha256 -extfile "$extension"
  else
    openssl x509 -req -in "$csr" -CA "$CRYPTO_DIR/ca.crt" -CAkey "$CRYPTO_DIR/ca.key" \
      -CAcreateserial -CAserial "$SERIAL_FILE" -out "$cert" -days 90 -sha256 -extfile "$extension"
  fi
}

# CA private key остаётся только на VPS и не монтируется в контейнеры.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$CRYPTO_DIR/ca.key"
openssl req -x509 -new -key "$CRYPTO_DIR/ca.key" -sha256 -days 3650 -out "$CRYPTO_DIR/ca.crt" \
  -subj '/CN=Fitness Dev mTLS CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign'

# Серверы проверяются по DNS SAN, callers — по URI SAN, который проверяет Java-код.
create_leaf identity-server identity-service spiffe://fitness.local/identity-service serverAuth
create_leaf accounts-server accounts-service spiffe://fitness.local/accounts-service serverAuth
create_leaf coins-server coins-service spiffe://fitness.local/coins-service serverAuth
create_leaf gateway-client gateway-service spiffe://fitness.local/gateway-service clientAuth
create_leaf identity-client identity-service spiffe://fitness.local/identity-service clientAuth

# Identity подписывает access token алгоритмом ES256: ключ должен быть PKCS#8 EC P-256.
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$CRYPTO_DIR/identity-jwt-private.pem"
openssl pkey -in "$CRYPTO_DIR/identity-jwt-private.pem" -pubout -out "$CRYPTO_DIR/identity-jwt-public.pem"

openssl verify -CAfile "$CRYPTO_DIR/ca.crt" "$CRYPTO_DIR/identity-server.crt" "$CRYPTO_DIR/accounts-server.crt" "$CRYPTO_DIR/coins-server.crt" "$CRYPTO_DIR/gateway-client.crt" "$CRYPTO_DIR/identity-client.crt" >/dev/null
openssl x509 -in "$CRYPTO_DIR/gateway-client.crt" -noout -text | grep -F 'URI:spiffe://fitness.local/gateway-service' >/dev/null
openssl x509 -in "$CRYPTO_DIR/identity-client.crt" -noout -text | grep -F 'URI:spiffe://fitness.local/identity-service' >/dev/null
openssl pkey -in "$CRYPTO_DIR/identity-jwt-private.pem" -pubout -out "$TEMP_DIR/jwt-public.pem"
cmp -s "$TEMP_DIR/jwt-public.pem" "$CRYPTO_DIR/identity-jwt-public.pem"

chown root:root "$CRYPTO_DIR/ca.key"
chmod 0600 "$CRYPTO_DIR/ca.key"
chown 10001:10001 "$CRYPTO_DIR"/*.crt "$CRYPTO_DIR"/*-pkcs8.key "$CRYPTO_DIR"/identity-jwt-*.pem
chmod 0644 "$CRYPTO_DIR"/*.crt "$CRYPTO_DIR"/identity-jwt-public.pem
chmod 0400 "$CRYPTO_DIR"/*-pkcs8.key "$CRYPTO_DIR"/identity-jwt-private.pem

echo "Созданы mTLS- и JWT-материалы в $CRYPTO_DIR"
echo "Листовые mTLS-сертификаты действуют 90 дней. Автоматической ротации нет."
