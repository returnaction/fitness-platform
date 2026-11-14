# Проверка Spec 017 — 2026-11-14

> **Статус:** in-progress; T001 выполнена, реализация не начата.

## T001 — 2026-11-14

Проверены исходники WalletEntity, WalletRepository, WalletCreationGrpcService,
SecurityConfiguration Coins, миграция coin_wallets, GatewayRouteCatalog,
UpstreamId, ForwardedHeadersFilter, конфигурация Gateway и Accounts
ProfileAuthenticationFilter. Сверены System Design README, карта контрактов,
документ 33 и существующие OpenAPI Accounts/Gateway.

Исправлен ID upstream Accounts: ACCOUNT. Уточнены существующий findById,
отсутствующие HTTP-компоненты Coins и обработка ошибок security-фильтра.
Существующее пользовательское изменение SecurityConfiguration Accounts сохранено.

`powershell -ExecutionPolicy Bypass -File scripts/verify-markdown-links.ps1`:
первый запуск в sandbox остановился на доступе к target/openapi-validation/attr;
повтор с разрешённым запуском вне sandbox — PASS, exit 0,
`Validated local Markdown links in 343 files.`

Сервисы, Maven-тесты и миграции не запускались: T001 проверяет исходники и документы.

## Среда и команды

### T002.1–T002.3 — 2026-11-14

Создан `contracts/openapi/coins-wallet-v1.yaml`: Bearer, GET, int64-балансы,
no-store, RequestId и общий ErrorResponse с обязательным details. Описаны
401, 500 (два кода), 503 и 400. Gateway подключает путь через $ref и protected
paths; upstream-errors документирует coins_unavailable отдельно от ошибок Gateway.
Обновлены contracts/README.md и contracts/gateway-v1.md; runtime явно отложен.

В системном Python модуль валидатора отсутствовал. Использованы существующие
зависимости target/openapi-validation через PYTHONPATH с разрешённым доступом
вне sandbox. Закреплённая версия openapi-spec-validator — 0.7.2.

Команды `python -m openapi_spec_validator contracts/openapi/<file>` — PASS
для coins-wallet-v1.yaml, gateway-v1.yaml, accounts-profile-v1.yaml и
identity-auth-v1.yaml (каждый результат OK).
`powershell -ExecutionPolicy Bypass -File scripts/verify-markdown-links.ps1` —
PASS, 343 файла. `git diff --check` — PASS.
Проверены описания и ссылки; HTTP-сервисы и БД не запускались.

Планируемая среда: Windows, локальные Java-процессы существующим способом и
локальный PostgreSQL. Фактическая среда фиксируется при выполнении.

Планируемые команды:

```powershell
./mvnw.cmd -pl services/coins-service -am spotless:apply verify
./mvnw.cmd -pl services/gateway-service -am spotless:apply verify
scripts/verify-markdown-links.ps1
git diff --check
```

## Результаты — не выполнялись

| Проверка | Ожидание | Факт |
| --- | --- | --- |
| GET /api/v1/coins/me через Gateway с действующей сессией | 200, баланс владельца | не проверялось |
| GET без сессии | 401 | не проверялось |
| Запрос чужого баланса | недоступен | не проверялось |
| Отсутствие кошелька активного пользователя | 500 `wallet_invariant_violation` | не проверялось |
| Временная ошибка БД/транзакции Coins | 503 `coins_unavailable` | не проверялось |
| Некорректный токен или `sub` | 401 `invalid_access_token` | не проверялось |
| Ответы Coins | `Cache-Control: no-store` | не проверялось |
| Контракты Coins/Gateway | owner OpenAPI + Gateway `$ref`, без дублирования схем | PASS T002; runtime не проверялся |
| Postman-коллекция | авторизованный GET баланса через Gateway и проверки 200 | не проверялось |
| coins-service | spotless:apply verify | не запускалось |
| gateway-service | spotless:apply verify | не запускалось |
| Markdown-ссылки | scripts/verify-markdown-links.ps1 | PASS в T001, 343 файла |
| Whitespace | git diff --check | PASS в T001 |

## Границы проверки

Результаты T001 приведены выше; проверки реализации остаются невыполненными.
Локальные проверки не доказывают защиту серверной среды. Commit, push, merge,
deploy и миграции пользовательских БД не выполнялись.
