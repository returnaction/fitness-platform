# Проверка Spec 017 — актуализация 2026-11-16

## Исправление классификации обрыва соединения — 2026-11-16

При проверке через локальный Gateway запрет подключений к fitness_coins и
закрытие соединений роли Coins дали 500 internal_error. В консоли Coins для
requestId `9e9525ba-5c34-468f-a832-1aeae4c3e9da` зарегистрирован JpaSystemException;
соседние сообщения содержат JDBCConnectionException, SQLState 08006 и
Connection reset by peer. Доступ к БД восстановлен, последующий GET дал 200.

Первое исправление, распознававшее только JDBCConnectionException в цепочке
причин, не устранило ошибку в сквозном сценарии. Интеграционный тест с обрывом
реального соединения выявил цепочку JpaSystemException → TransactionException
→ SQLException: ошибка rollback заменяла исходную ошибку соединения.
Hikari возвращал `Connection is closed` без SQLState.

WalletQueryService распознаёт JDBCConnectionException, а также вложенную в
TransactionException ошибку соединения с SQLState класса 08 или указанный
случай закрытого соединения без SQLState. Они преобразуются в
CoinsUnavailableException. Остальные JPA-ошибки не маскируются.
Добавлены unit-проверки классификации и repository-тест реального обрыва
соединения с последующим восстановлением чтения.

`./mvnw.cmd -B -ntp -pl services/coins-service -am verify` — BUILD SUCCESS:
48 тестов Coins и 3 теста gRPC contracts, без ошибок и пропусков; SpotBugs —
без замечаний. Интеграционные тесты использовали временный PostgreSQL в Docker.
После запуска обновлённых сервисов повторён настоящий GET через Gateway:

- До сбоя: 200, availableBalance=0, lockedBalance=0.
- Временно запрещены подключения только к fitness_coins и завершены три
  соединения роли fitness_coins_app. GET вернул 503 `coins_unavailable`
  за 126 мс; requestId `04df9870-dc9e-4ec8-8e78-7e2c04d5cc13` совпадает
  в JSON и заголовке, присутствует `Cache-Control: no-store`.
- Доступ к БД восстановлен в finally; следующий GET вернул 200 за 133 мс.

Проверка выполнялась без создания файлов скриптов. Создан локальный тестовый
пользователь. Таймауты пула не менялись; этот сценарий подтверждает обработку
обрыва существующего соединения, а не всех вариантов длительной недоступности БД.

## Дополнительные unit-тесты после merge — 2026-11-16

Добавлены `WalletQueryServiceTest` и `WalletAuthenticationFilterTest`.
Настоящий WalletQueryService проверяется с mock repository: четыре типа
временных ошибок сохраняются как cause CoinsUnavailableException, неожиданные
ошибки не маскируются, отсутствующий кошелёк не создаётся автоматически.
Фильтр проверяется на UUID и роли в SecurityContext, игнорирование неизвестных
ролей, отсутствие переноса ролей между запросами, очистку контекста после
успеха, downstream-исключения и отказа аутентификации. Некорректные токены,
subject и типы roles не допускаются до следующего звена цепочки.

```powershell
./mvnw.cmd -B -ntp -pl services/coins-service -am "-Dtest=WalletQueryServiceTest,WalletAuthenticationFilterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Результат: BUILD SUCCESS, 17 тестов, 0 failures/errors/skipped; JDK 25.0.5.
Первый запуск остановился на доступе к Maven repository в sandbox;
повтор с расширенным доступом прошёл. БД, Docker и сервисы не запускались.
Это проверка unit-сценариев, не повторный полный verify или сквозная
проверка HTTP 503 при реальной недоступности БД.

## История исходной реализации — 2026-11-14–15

Разделы ниже сохраняют результаты коллеги на момент исходной реализации.
Они не означают повторного запуска на текущей ветке после merge и ревью.
Явные таймауты Hikari и Python e2e-скрипт из этих разделов впоследствии
удалены пользователем. Актуальная проверка обрыва соединения приведена выше.
Полный Maven build пользователь подтвердил 16 ноября до последнего исправления;
после исправления повторён Coins verify. Контракты после ревью ещё не перепроверены.

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

Команды проверки (актуализированы: Spotless отключён инженерными стандартами):

```powershell
./mvnw.cmd -pl services/coins-service -am verify
./mvnw.cmd -pl services/gateway-service -am verify
scripts/verify-markdown-links.ps1
git diff --check
```

## T003–T004 — 2026-11-15

Реализованы upstream `COINS`, маршрут `GET /api/v1/coins/me` и REST API Coins.
Gateway пересылает Bearer и канонический `X-Request-Id`, Coins извлекает `sub`
из forwarded JWT без повторной проверки подписи и сессии.

Проверки:

```text
./mvnw -pl services/gateway-service -am verify  -> BUILD SUCCESS, 48 tests, SpotBugs OK
./mvnw -pl services/coins-service -am verify    -> BUILD SUCCESS, 26 tests, SpotBugs OK
```

Покрыты: repository-чтение, HTTP 200, отсутствие Bearer, невалидный `sub`,
отсутствующий кошелёк, временная ошибка БД, формат ошибок и `no-store`.

## T005.1–T005.4 — локальный сквозной запуск, 2026-11-15

Среда: macOS, JDK 25.0.5, Homebrew PostgreSQL 17 на `127.0.0.1:5432`,
OpenSSL 3.6. Локальные `secrets/` и `.env` созданы эквивалентно
`initialize-local-env.ps1`; пароли и токены не записывались в отчёт и Git.
Запущены реальные Accounts, Coins, Identity и Gateway (профиль `local`).

| Проверка | Факт |
| --- | --- |
| Регистрация через Gateway | `201`, пользователь и кошелёк созданы |
| Логин | `200`, активная сессия |
| Кошелёк в `fitness_coins` | 1 запись |
| `GET /api/v1/coins/me` с сессией | `200`, `{"availableBalance":10,"lockedBalance":20}` |
| `Cache-Control` успешного ответа | `no-store` |
| GET без Bearer | `401 invalid_access_token`, `WWW-Authenticate: Bearer`, `no-store` |
| Malformed Bearer | `401` |
| Второй пользователь | видит только свой `0/0` |
| `?userId=<чужой>` | `400 invalid_request` |
| Поддельный `X-User-Id` | игнорируется, возвращён баланс владельца `10/20` |
| Удалённый кошелёк | `500 wallet_invariant_violation`, `requestId`, `no-store` |
| `fitness_coins` недоступна | `503 coins_unavailable`, `requestId`, `no-store` |
| Восстановление БД | снова `200` и баланс `10/20` |

При проверке 503 обнаружен и исправлен дефект: `@Transactional(readOnly = true)`
на `WalletQueryService` открывал транзакцию до входа в метод, из-за чего ошибка
соединения возвращалась как `500 internal_error`. Аннотация удалена; `findById`
остаётся read-only транзакцией Spring Data. На момент исходной проверки в Coins были заданы
`spring.datasource.hikari.connection-timeout: 3000` и `validation-timeout: 1000`:
в обычной конфигурации `503 coins_unavailable` пришёл за 3.07s, до deadline
Gateway 7s, без тестовых override. Эти настройки затем удалены пользователем;
результат 3.07s не характеризует текущую конфигурацию.

Историческое ограничение: одноразовый e2e-сценарий
`services/gateway-service/src/test/python/verify_gateway_e2e.py` не завершил
регистрацию в контейнерной среде — Identity записал
`last_error_code=ACCOUNTS_UNAVAILABLE` при gRPC-вызове Accounts. Это
инфраструктурное ограничение проверки, а не подтверждённый успех; локальный
сквозной сценарий выше выполнен отдельно. Скрипт впоследствии удалён
по решению пользователя; восстанавливать его в рамках этой задачи не требуется.

## Результаты исходной реализации — 2026-11-15

| Проверка | Ожидание | Факт |
| --- | --- | --- |
| GET /api/v1/coins/me через Gateway с действующей сессией | 200, баланс владельца | PASS T005.2 |
| GET без сессии | 401 | PASS T005.3 |
| Запрос чужого баланса | недоступен | PASS T005.3 |
| Отсутствие кошелька активного пользователя | 500 `wallet_invariant_violation` | PASS T005.4 |
| Временная ошибка БД/транзакции Coins | 503 `coins_unavailable` | PASS T005.4 в обычной конфигурации, 3.07s |
| Некорректный токен или `sub` | 401 `invalid_access_token` | PASS T004.11/T005.3 |
| Ответы Coins | `Cache-Control: no-store` | PASS T004.11/T005.2–T005.4 |
| Контракты Coins/Gateway | owner OpenAPI + Gateway `$ref`, без дублирования схем | PASS T002 |
| Postman-коллекция | авторизованный GET баланса через Gateway и проверки 200 | PASS T005.6, JSON валиден |
| coins-service | Maven verify | PASS T004.12 |
| gateway-service | Maven verify | PASS T003.6 |
| Markdown-ссылки | scripts/verify-markdown-links.ps1 | PASS T001 |
| Whitespace | git diff --check | PASS T006 |
| Итоговый verify Gateway/Coins | Maven verify, SpotBugs | PASS T006: 48 + 26 тестов |
| Markdown-ссылки повторно | локальные ссылки | PASS T006: 173 файла |

## Границы проверки

Локальный сквозной сценарий подтверждает HTTP-поведение в среде разработки,
но не защиту серверной среды. Старый контейнерный e2e не прошёл
из-за `ACCOUNTS_UNAVAILABLE`, затем его скрипт удалён. Длительная недоступность
БД на текущих настройках остаётся непроверенной; обрыв существующего соединения
проверен 16 ноября. См. отложенное решение в [questions.md](questions.md). Commit, push, merge, deploy и
миграции пользовательских БД не выполнялись.

## Актуализация документов — 2026-11-16

Согласованы spec.md, plan.md, tasks.md и questions.md. Исходные результаты
отделены от проверок после ревью; удалённые таймауты и Python-скрипт отмечены
как исторические. Длительная недоступность БД и повторная проверка контрактов
оставлены открытыми. Код сервисов в этом шаге не менялся.

`scripts/verify-markdown-links.ps1` — PASS, 351 файл (повтор вне sandbox
после ограничения доступа к target/openapi-validation). `git diff --check`
— PASS. Maven и HTTP-сценарии при обновлении документов повторно не запускались.
