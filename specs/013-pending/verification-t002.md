# Проверка T002 — 2026-11-12

Исторический результат T002 до T002a (удаление rate limiter) и T002b (возврат HTTP).
HTTPS и проверка сертификатов HTTP из этого отчёта отменены в T002b;
эти результаты не доказывают защиту текущего HTTP.
В T002a ClientIpAddressResolver и rate limiter удалены; актуальные результаты — в tasks.md.

## Реализация

- Identity: обязательный HTTP mTLS; проверка точного URI SAN Gateway до
  пользовательской аутентификации. Только пять auth POST и GET JWKS.
- Management: отдельный HTTP listener 127.0.0.1:9080, только предусмотренные GET;
  API на нём запрещён, management на API listener запрещён.
- Recovery закрыт для всех HTTP callers. Сохранены его verifier, бизнес-код и аудит.
- JWT logout-all и LogoutService не изменены. Gateway HTTP-клиенты не изменены.
- Accounts/Coins local HTTP привязан к 127.0.0.1. Identity local уже был на loopback.
  gRPC-конфигурация и логика сохранены. Forwarded headers не переписывают адрес
  соединения до существующего ClientIpAddressResolver.
- Добавлены четыре отсутствующих параметра HTTPS/management в локальный .env
  Identity, существующие значения и сертификаты не заменялись. Шаблон обновлён.
- Spotless потребовал также пустые строки в существующих Java-файлах; эти
  изменения форматирования не меняют бизнес-логику.

## Сборка и автоматические проверки

Maven Wrapper, Temurin 25.0.5, зависимости проекта без изменений.
Общий параметр команд: `-Dmaven.repo.local=C:\Users\Oberg\.m2\repository -B -ntp`.

- `-pl services/identity-service,services/accounts-service,services/coins-service -am spotless:apply verify`:
  в повторном прогоне все 141 тест Identity завершились без ошибок, 2 существующих
  opt-in local smoke пропущены. Затем SpotBugs обнаружил хранение JsonMapper.
- Замечание исправлено использованием ObjectWriter. Итоговая команда Identity:
  `-pl services/identity-service -am -Dtest=GatewayCallerFilterTest,RequestIdFilterSecurityTest -Dsurefire.failIfNoSpecifiedTests=false spotless:apply verify` —
  BUILD SUCCESS, 7 адресных тестов, Spotless/SpotBugs прошли.
- `-pl services/accounts-service,services/coins-service -am verify`: 23 теста
  Accounts без ошибок, 1 opt-in smoke пропущен; остановка на существующем форматировании.
  После spotless:apply команда `-pl services/accounts-service -am -DskipTests spotless:apply verify`
  завершилась успешно; уже прошедшие тесты не повторялись.
- `-pl services/coins-service -am spotless:apply verify`: BUILD SUCCESS,
  18 тестов Coins без ошибок и пропусков; Spotless/SpotBugs прошли.
  Контракты: 3 теста без ошибок.
- Реальный запуск выявил дубль server в local YAML Identity; дубль удалён,
  loopback Coins добавлен. Финальная пересборка
  `-pl services/identity-service,services/coins-service -am -DskipTests verify` —
  BUILD SUCCESS. Эти изменения local-профиля проверены последующим запуском.

## Реальное локальное приложение

Запущены собранные Java-приложения Identity, Accounts, Coins с существующими
локальными .env, PostgreSQL и сертификатами. Во всех трёх процессах
`--spring.liquibase.enabled=false`; миграции пользовательских БД не запускались.
Тестового пользователя на T002 создавать не потребовалось.
После проверки остановлены только процессы, запущенные этой проверкой.

- Gateway certificate -> JWKS: `200` — PASS.
- Untrusted client certificate: `TLS_REJECTED` — PASS.
- No certificate -> JWKS: `TLS_REJECTED` — PASS.
- Identity certificate cannot act as Gateway: `403` — PASS.
- Untrusted server CA: `TLS_REJECTED` — PASS.
- Incorrect server hostname: `TLS_REJECTED` — PASS.
- Management on API port denied: `403` — PASS.
- Recovery denied even to Gateway: `403` — PASS.
- Gateway certificate does not replace logout JWT: `401` — PASS.
- Unknown operation denied: `403` — PASS.
- Wrong method denied: `403` — PASS.
- Readiness through management: `200` — PASS.
- Liveness through management: `200` — PASS.
- Metrics through management: `200` — PASS.
- Auth cannot use management port: `403` — PASS.
- Plaintext cannot use API port: `400` — PASS.
- Local listener 8081: `True` — PASS.
- Local listener 8082: `True` — PASS.
- Local listener 9091: `True` — PASS.
- Local listener 9092: `True` — PASS.
- Local listener 9093: `True` — PASS.
- Loopback 9093: `127.0.0.1` — PASS.
- Loopback 9092: `127.0.0.1` — PASS.
- Loopback 9091: `127.0.0.1` — PASS.
- Loopback 9080: `127.0.0.1` — PASS.
- Loopback 8082: `127.0.0.1` — PASS.
- Loopback 8081: `127.0.0.1` — PASS.
- Loopback 8080: `127.0.0.1` — PASS.

Локальные артефакты: `target/verify_t002_local.py`,
`target/t002-local-results.json`, `target/t002-identity.log`,
`target/t002-accounts.log`, `target/t002-coins.log`; это рабочие файлы, не секреты
для публикации. Временный недоверенный сертификат использован только для негативной пробы.

## Границы доказательств

Реальный Gateway не подключался: его HTTP/JWKS клиенты пока не умеют этот mTLS;
это T003. HTTPS-пробы представляли Gateway его существующим сертификатом.
Рабочие gRPC listeners подтверждены, поведение gRPC проверено существующими
автоматическими тестами; полный сквозной путь с Gateway относится к T004.
Локальный bind проверен по фактическим listeners. Внешний firewall и контейнерное
развёртывание не проверялись. Проверки сертификата с истёкшим сроком и полного
запуска Gateway/JWKS readiness остаются частью T004.

Результат: T002 выполнен. Остановка для ревью пользователя; T003 не начат.
Коммит, push, merge и deploy агент не выполнял.
