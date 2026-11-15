#!/usr/bin/env bash
# На VPS: один lock и один последовательный цикл на весь выпуск.
# Вход: каталог приложения, временный каталог выпуска, номер GitHub run.
set -Eeuo pipefail
app_dir="$1"
release_dir="$2"
run_number="$3"
[[ "$run_number" =~ ^[0-9]+$ ]]
compose_env="$app_dir/compose.env"
compose_file="$app_dir/compose.yaml"
test -f "$compose_env"
test -s "$release_dir/candidates.tsv"
test -f "$release_dir/compose.yaml"
exec 9>"$app_dir/.deploy.lock"
flock -w 600 9

compose() { docker compose --env-file "$compose_env" -f "$compose_file" "$@"; }
service=""
report_failure() {
  status=$?
  trap - ERR
  echo "Ошибка выпуска ${service}; дальнейшая выкладка остановлена" >&2
  compose ps || true
  if [[ -n "$service" ]]; then compose logs --tail=200 "$service" || true; fi
  exit "$status"
}
trap report_failure ERR

# Проверяем весь список до изменения Compose. Порядок задаёт генератор кандидатов.
previous_rank=0
while IFS=$'\t' read -r service version; do
  case "$service" in
    accounts-service) rank=1 ;;
    coins-service) rank=2 ;;
    identity-service) rank=3 ;;
    gateway-service) rank=4 ;;
    *) exit 2 ;;
  esac
  [[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]]
  (( rank > previous_rank ))
  previous_rank=$rank
done < "$release_dir/candidates.tsv"

# Старый run не перезаписывает Compose, уже доставленный новым run.
compose_run=0
if [[ -f "$app_dir/.compose-run" ]]; then read -r compose_run < "$app_dir/.compose-run"; fi
[[ "$compose_run" =~ ^[0-9]+$ ]]
if (( run_number >= compose_run )); then
  cp "$release_dir/compose.yaml" "$compose_file"
  printf '%s\n' "$run_number" > "$app_dir/.compose-run"
fi
compose config --quiet

while IFS=$'\t' read -r service version; do
  case "$service" in
    accounts-service) image_variable=ACCOUNTS_IMAGE; dependencies=() ;;
    coins-service) image_variable=COINS_IMAGE; dependencies=(accounts-service) ;;
    identity-service) image_variable=IDENTITY_IMAGE; dependencies=(accounts-service coins-service) ;;
    gateway-service) image_variable=GATEWAY_IMAGE; dependencies=(identity-service) ;;
  esac
  [[ $(grep -c "^${image_variable}=" "$compose_env") == 1 ]]
  current_image=$(sed -n "s/^${image_variable}=//p" "$compose_env")
  current_version=${current_image##*:}
  [[ "$current_version" =~ ^[0-9]+(\.[0-9]+)*$ ]]
  highest=$(printf '%s\n%s\n' "$current_version" "$version" | sort -V | tail -n 1)
  if [[ "$highest" != "$version" ]]; then
    echo "$service: пропущен устаревший выпуск $version (уже задан $current_version)"
    continue
  fi

  # --no-deps не запускает зависимости: прежде убеждаемся, что они здоровы.
  for dependency in "${dependencies[@]}"; do
    container=$(compose ps -q "$dependency")
    test -n "$container"
    [[ $(docker inspect --format '{{.State.Health.Status}}' "$container") == healthy ]]
  done
  image="ghcr.io/returnaction/fitness-${service}:${version}"
  sed -i "s|^${image_variable}=.*$|${image_variable}=${image}|" "$compose_env"
  compose pull "$service"
  compose up -d --no-deps --wait --wait-timeout 120 "$service"
  # При неуспехе up trap завершит весь цикл; отката и удаления образов нет.
  docker image prune --all --force \
    --filter 'label=org.opencontainers.image.source=https://github.com/returnaction/fitness-platform'
done < "$release_dir/candidates.tsv"
