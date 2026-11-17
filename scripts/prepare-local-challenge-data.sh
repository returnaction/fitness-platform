#!/usr/bin/env bash
# Готовит локальные данные для сквозной проверки Challenge:
# двух пользователей Identity, две сессии и два кошелька с тестовыми монетами.
# Секреты и токены пишутся только в игнорируемый каталог secrets/.
set -Eeuo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_FILE="${CHALLENGE_TEST_DATA_FILE:-$ROOT_DIR/secrets/challenge-local-test-data.env}"
IDENTITY_URL="${IDENTITY_URL:-http://localhost:8080}"
COINS_DB_HOST="${COINS_DB_HOST:-localhost}"
COINS_DB_PORT="${COINS_DB_PORT:-5432}"
COINS_DB_NAME="${COINS_DB_NAME:-fitness_coins}"
COINS_DB_USER="${COINS_DB_USER:-fitness_coins_app}"
COINS_DB_PASSWORD="${COINS_DB_PASSWORD:-}"
SEED_AMOUNT="${CHALLENGE_SEED_AMOUNT:-20}"
USER1_EMAIL="${CHALLENGE_USER1_EMAIL:-challenge-seed-1@example.test}"
USER2_EMAIL="${CHALLENGE_USER2_EMAIL:-challenge-seed-2@example.test}"

for command in curl jq psql openssl python3; do
  command -v "$command" >/dev/null || { echo "Не найден $command" >&2; exit 1; }
done

if [[ -f "$DATA_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$DATA_FILE"
  USER1_EMAIL="${CHALLENGE_USER1_EMAIL:-$USER1_EMAIL}"
  USER2_EMAIL="${CHALLENGE_USER2_EMAIL:-$USER2_EMAIL}"
  CHALLENGE_SEED_PASSWORD="${CHALLENGE_SEED_PASSWORD:-}"
fi

if [[ -z "${CHALLENGE_SEED_PASSWORD:-}" ]]; then
  CHALLENGE_SEED_PASSWORD="$(openssl rand -base64 24 | tr -d '\n')"
fi

# Локальный .env Coins содержит пароль роли; сам файл исключён из Git.
if [[ -f "$ROOT_DIR/services/coins-service/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/services/coins-service/.env"
  set +a
  if [[ -z "$COINS_DB_PASSWORD" && -n "${DATABASE_PASSWORD:-}" ]]; then
    COINS_DB_PASSWORD="$DATABASE_PASSWORD"
  fi
  if [[ -n "${DATABASE_URL:-}" && "$COINS_DB_HOST" == "localhost" ]]; then
    if [[ "$DATABASE_URL" =~ ^jdbc:postgresql://([^:/]+):([0-9]+)/(.+)$ ]]; then
      COINS_DB_HOST="${BASH_REMATCH[1]}"
      COINS_DB_PORT="${BASH_REMATCH[2]}"
      COINS_DB_NAME="${BASH_REMATCH[3]}"
    fi
  fi
  COINS_DB_USER="${COINS_DB_USER:-${DATABASE_USERNAME:-fitness_coins_app}}"
fi

if [[ -z "$COINS_DB_PASSWORD" ]]; then
  echo "Задайте COINS_DB_PASSWORD или создайте services/coins-service/.env." >&2
  exit 1
fi

json_field() {
  local json="$1"
  local path="$2"
  printf '%s' "$json" | jq -er "$path"
}

login_or_register() {
  local email="$1"
  local body code response token
  body="$(jq -nc --arg email "$email" --arg password "$CHALLENGE_SEED_PASSWORD" \
    '{email:$email,password:$password}')"
  response="$(curl -sS -w $'\n%{http_code}' -X POST "$IDENTITY_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d "$body")"
  code="${response##*$'\n'}"
  response="${response%$'\n'*}"
  if [[ "$code" == "200" ]]; then
    token="$(json_field "$response" '.accessToken')"
  elif [[ "$code" == "401" ]]; then
    local key
    key="$(python3 -c 'import uuid; print(uuid.uuid4())')"
    response="$(curl -sS -w $'\n%{http_code}' -X POST "$IDENTITY_URL/api/v1/auth/register" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body")"
    code="${response##*$'\n'}"
    response="${response%$'\n'*}"
    if [[ "$code" != "201" ]]; then
      echo "Регистрация $email не удалась: HTTP $code" >&2
      printf '%s\n' "$response" >&2
      exit 1
    fi
    token="$(json_field "$response" '.session.accessToken')"
  else
    echo "Вход $email не удался: HTTP $code" >&2
    printf '%s\n' "$response" >&2
    exit 1
  fi
  printf '%s' "$token"
}

user_id_from_token() {
  python3 - "$1" <<'PY'
import base64
import json
import sys

payload = sys.argv[1].split(".")[1]
payload += "=" * (-len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload))["sub"])
PY
}

credit_wallet() {
  local user_id="$1"
  local operation_id
  operation_id="$(python3 - "$user_id" <<'PY'
import sys
import uuid

print(uuid.uuid5(uuid.NAMESPACE_URL, "fitness-local-challenge-seed:" + sys.argv[1]))
PY
)"
  PGPASSWORD="$COINS_DB_PASSWORD" psql -q -v ON_ERROR_STOP=1 \
    -h "$COINS_DB_HOST" -p "$COINS_DB_PORT" -U "$COINS_DB_USER" -d "$COINS_DB_NAME" \
    -v user_id="$user_id" -v operation_id="$operation_id" -v amount="$SEED_AMOUNT" >/dev/null <<'SQL'
-- Локальный seed для теста: отдельного типа награды в MVP нет, поэтому
-- используется разрешённый ledger type SETTLEMENT с детерминированным operation_id.
BEGIN;
WITH inserted AS (
    INSERT INTO coin_ledger_entries
        (id, user_id, operation_id, entry_index, type, available_delta, locked_delta, challenge_id)
    VALUES
        (gen_random_uuid(), :'user_id'::uuid, :'operation_id'::uuid, 0, 'SETTLEMENT',
         :'amount'::bigint, 0, NULL)
    ON CONFLICT (operation_id, entry_index) DO NOTHING
    RETURNING 1
)
UPDATE coin_wallets
SET available_balance = available_balance + :'amount'::bigint,
    updated_at = CURRENT_TIMESTAMP
WHERE user_id = :'user_id'::uuid
  AND EXISTS (SELECT 1 FROM inserted);
COMMIT;
SQL
  local balance
  balance="$(PGPASSWORD="$COINS_DB_PASSWORD" psql -q -v ON_ERROR_STOP=1 -tA \
    -h "$COINS_DB_HOST" -p "$COINS_DB_PORT" -U "$COINS_DB_USER" -d "$COINS_DB_NAME" \
    -v user_id="$user_id" <<'SQL'
SELECT available_balance FROM coin_wallets WHERE user_id = :'user_id'::uuid;
SQL
)"
  if [[ -z "$balance" ]]; then
    echo "Кошелёк $user_id не найден. Регистрация не завершила создание кошелька." >&2
    exit 1
  fi
  printf '%s' "$balance"
}

USER1_TOKEN="$(login_or_register "$USER1_EMAIL")"
USER2_TOKEN="$(login_or_register "$USER2_EMAIL")"
USER1_ID="$(user_id_from_token "$USER1_TOKEN")"
USER2_ID="$(user_id_from_token "$USER2_TOKEN")"
USER1_BALANCE="$(credit_wallet "$USER1_ID")"
USER2_BALANCE="$(credit_wallet "$USER2_ID")"

mkdir -p "$(dirname "$DATA_FILE")"
cat >"$DATA_FILE" <<EOF
CHALLENGE_SEED_PASSWORD='$CHALLENGE_SEED_PASSWORD'
CHALLENGE_USER1_ID='$USER1_ID'
CHALLENGE_USER1_EMAIL='$USER1_EMAIL'
CHALLENGE_USER1_TOKEN='$USER1_TOKEN'
CHALLENGE_USER2_ID='$USER2_ID'
CHALLENGE_USER2_EMAIL='$USER2_EMAIL'
CHALLENGE_USER2_TOKEN='$USER2_TOKEN'
CHALLENGE_USER1_BALANCE='$USER1_BALANCE'
CHALLENGE_USER2_BALANCE='$USER2_BALANCE'
EOF

echo "Готовы две локальные сессии и кошельки."
echo "Файл данных: $DATA_FILE (исключён из Git)"
echo "user1=$USER1_ID balance=$USER1_BALANCE"
echo "user2=$USER2_ID balance=$USER2_BALANCE"
