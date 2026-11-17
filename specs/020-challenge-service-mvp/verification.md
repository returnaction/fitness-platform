# Проверка Spec 020

> **Спецификация:** [spec.md](spec.md)
> **Статус:** проверки фиксируются по задачам; записи — в истории ниже.
> **Ветка:** `feature/50-challenge-service-dev`
> **Окружение:** macOS, Eclipse Temurin 25.0.5, Docker доступен, PostgreSQL локально, Python 3.13. `pwsh` локально не установлен; GitHub Actions использует `pwsh`.

## Команды проверки

Maven-команды выполняются из корня репозитория:

```bash
./mvnw -B -ntp -pl services/gateway-service -am verify
./mvnw -B -ntp -pl services/challenge-service -am verify
./mvnw -B -ntp -pl services/coins-service -am verify
git diff --check
```

Проверка контрактов — в отдельном виртуальном окружении, версии закреплены в `contracts/openapi/requirements-validation.txt`:

```bash
python3 -m venv .venv-openapi
. .venv-openapi/bin/activate
python -m pip install -r contracts/openapi/requirements-validation.txt
python -m openapi_spec_validator contracts/openapi/challenge-v1.yaml
python -m openapi_spec_validator contracts/openapi/gateway-v1.yaml
```

Проверка Markdown-ссылок:

```bash
# Если PowerShell 7 установлен локально.
pwsh ./scripts/verify-markdown-links.ps1
```

Если `pwsh` отсутствует, локальная проверка фиксируется как невыполненная, а результат CI приводится отдельно. Нельзя записывать непроверенные ссылки как успешные.

Testcontainers-тесты требуют работающего Docker. Без Docker они пропускаются по существующему правилу `disabledWithoutDocker`; это ограничение, а не успешная проверка.

## План сквозных проверок

| Проверка | Ожидание | Факт |
| --- | --- | --- |
| Publish через Gateway с действующей сессией | `200`/`202`, резерв создателя в Coins, создатель `JOINED` | — |
| Publish без монет | `409 insufficient_coins`, черновик остаётся `DRAFT`, hold нет | — |
| Join второго участника | `200`/`202`; после seal challenge `ACTIVE`, набор `SEALED` | — |
| Два параллельных join за последнее место | один подтверждён, второй получает `409 challenge_full` только после release | — |
| Join без токена | `401 unauthenticated` в едином формате с `requestId` | — |
| Чужая операция | `404 operation_not_found` | — |
| Cancel создателем без других участников | `CANCELLED`, hold освобождён один раз, `refundStatus PENDING → COMPLETED` | — |
| Cancel после вступления второго | `409 cancellation_not_allowed` | — |
| Внутренний result `SINGLE_WINNER` | `FINAL`, winners и digest зафиксированы, outbox создан | — |
| Settlement после результата | баланс победителя +банк, `settlementStatus COMPLETED`, событие выпущено | — |
| `DRAW` | обе ставки возвращены, `winnersCount=2`, побед в статистике 0 | — |
| Повтор с тем же `Idempotency-Key` | сохранённый ответ, без второго резерва | — |
| Повтор с другим телом | `409 idempotency_key_conflict` | — |
| Потерянный ответ Coins | операция остаётся `PENDING`, клиент получает `202`, повтор по operation ID не создаёт второй резерв | — |
| Coins недоступна | для начатой операции `202`; `503` только при невозможности сохранить/прочитать операцию | — |
| Рестарт recovery worker | незавершённая операция продолжена без дубля hold | — |
| Внутренний `FinalizeResult` через публичный Gateway | маршрут отсутствует; прямой вызов без mTLS отклонён | — |
| Повтор `coins.settlement_completed` | баланс не меняется второй раз, `settlementStatus` остаётся `COMPLETED` | — |

## План проверок по модулям

### Gateway

- [ ] `CHALLENGE_HTTP_BASE_URL` обязателен; без него контекст не стартует.
- [ ] Каталог содержит все challenge-маршруты с `AUTHENTICATED`; внутреннего result нет.
- [ ] JWT validation и CheckSession выполняются для защищённых маршрутов.
- [ ] `Authorization`, `Idempotency-Key` и канонический `X-Request-Id` доходят до upstream.
- [ ] Динамические UUID-сегменты проксируются; query string отклоняется с `400`.
- [ ] Ошибки upstream передаются в общем формате.

### Challenge: данные и домен

- [ ] Liquibase создаёт только таблицы MVP; CHECK и уникальные индексы работают.
- [ ] Переходы `DRAFT→OPEN→ACTIVE→FINISHED` и `OPEN→CANCELLED` сериализуются.
- [ ] `version` конфликтует корректно; `next_join_sequence` не переиспользуется.
- [ ] Незавершённая операция одна на `(challenge_id, user_id)`.
- [ ] Release intent один на `hold_id`.

### Challenge: HTTP и security

- [ ] `201` с `Location`, `200`, `202` со `statusUrl`, `400`, `401`, `404`, `409`, `422`, `503`.
- [ ] `Idempotency-Key` обязателен; повтор с тем же ключом и телом возвращает сохранённый ответ.
- [ ] Другое тело с тем же ключом — `409 idempotency_key_conflict`.
- [ ] Чужой DRAFT — `404`; чужая операция — `404`.
- [ ] `SecurityContext` очищается после запроса.
- [ ] `requestId` присутствует в успешных и ошибочных ответах.

### Challenge: конкурентность и отказы

- [ ] Два join за последнее место: ровно один подтверждён; второй release подтверждён.
- [ ] Cancel одновременно с join не подтверждает участие после отмены.
- [ ] Потерянный ответ Coins до commit не создаёт participation.
- [ ] Потерянный ответ Coins после commit восстанавливается по operation ID.
- [ ] Повтор `FinalizeResult` с тем же `resultId` не создаёт вторую запись.
- [ ] `MULTIPLE_WINNERS` отклоняется.
- [ ] Тот же `event_id` с другим digest — карантин, без изменения состояния.

### Coins

- [ ] Reserve обновляет available/locked, hold и ledger одной транзакцией.
- [ ] Недостаток монет не меняет баланс; повтор возвращает сохранённый исход.
- [ ] Release освобождает hold ровно один раз; двойной release разными ID отклоняется.
- [ ] Begin/Put/Finalize сверяют count/digest/active holds; новый reserve после SEALED запрещён.
- [ ] Freeze запрещает новые резервы; Complete не проходит при активном hold.
- [ ] Settlement `SINGLE_WINNER`/`DRAW` сохраняет сумму, ledger и outbox; повтор не платит дважды.
- [ ] `coins.settlement_completed` публикуется только после успешного расчёта.

## Границы проверки

Локальный сквозной сценарий подтверждает HTTP/gRPC-поведение в среде разработки, но не серверную защиту и не production-отказоустойчивость. Docker Compose с Kafka не доказывает production-кластер. Миграции пользовательских БД, выкладка и release выполняются только по отдельному разрешению. Недоступность Docker, сертификатов, Kafka или PostgreSQL фиксируется как непроверенный критерий, а не как успех.

Сценарий длительной недоступности БД унаследован из Spec 017 и не считается пройденным до измерения.

## История

Записи добавляются после каждой разрешённой проверки: дата, команда, `requestId`, результат.

### 2026-11-17 — T001, подготовка спецификации

- Созданы документы Spec 020. `AGENTS.md` и `specs/README.md` не изменялись по решению пользователя.
- Локальная проверка относительных Markdown-ссылок: 1006 ссылок, ошибок нет.
- `git diff --check`: успешно.
- Код, миграции, БД, commit, push и deploy не выполнялись.

### 2026-11-17 — T002.1, OpenAPI Challenge

- Создан `contracts/openapi/challenge-v1.yaml`.
- `python -m openapi_spec_validator contracts/openapi/challenge-v1.yaml` в отдельном venv: `OK`.
- Использованы лимиты, подтверждённые пользователем: title 1–120, description до 2000, stake 1–1 000 000, duration 1–30.
- `git diff --check`: успешно.
- Код, миграции, БД, commit, push и deploy не выполнялись.

### 2026-11-17 — T002.2, gRPC-контракт ставок

- Создан `contracts/proto/coins-stake-v1.proto`: `ReserveStake`, `ReleaseStake`, `GetStakeOperation`, `BeginStakeSet`, `PutStakeBatch`, `FinalizeStakeSet`, `FreezeStakeSet`, `CompleteStakeCancellation`. `ListActiveHoldsForCancellation` не включён по решению 16.
- Пакет `fitness.coins.stake.v1`, Java-пакет `io.github.returnaction.fitness.contracts.coins.stake.v1`. Описаны бизнес-исходы reserve/release, идемпотентность по operation ID, `WALLET_NOT_FOUND` как `FAILED_PRECONDITION`, таблица gRPC-кодов и reason.
- `fitness-grpc-contracts` 0.1.1 → 0.1.2. Обновлены зависимости: identity 0.1.2 (сервис 0.1.2), accounts 0.1.3, coins 0.1.3, gateway 0.1.3; `challenge-service` получил зависимость и версию 0.1.2.
- `sh ./mvnw -B -ntp -pl contracts/grpc-contracts verify`: `BUILD SUCCESS`, тесты 4/4, SpotBugs 0.
- `sh ./mvnw -B -ntp -pl services/challenge-service,services/coins-service -am clean test-compile`: `BUILD SUCCESS`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: 1003 ссылки, ошибок нет.
- Код сервисов, БД, Kafka, deployment, commit, push и deploy не выполнялись.
- Ограничение окружения: `./mvnw` в рабочей копии имеет mode `100644`, команды запускались через `sh ./mvnw`.

### 2026-11-17 — T002.3, gRPC-контракт результата

- Создан `contracts/proto/challenge-results-v1.proto`: `FinalizeResult` и `ListResultWinners`. Пакет `fitness.challenge.results.v1`, Java-пакет `io.github.returnaction.fitness.contracts.challenge.results.v1`.
- `FinalizeResult` принимает `resultId`, `challengeId`, `setVersion`, `setDigest`, `outcome`, `ruleVersion`, список победителей; возвращает `winnerCount` и `winnersDigest`, вычисленные Challenge. `MULTIPLE_WINNERS` зарезервирован и в MVP отклоняется.
- Зафиксированы mTLS-правила: `FinalizeResult` — только доверенный источник результата с отдельным SAN, `ListResultWinners` — только `coins-service`; через Gateway контракт недоступен.
- Конфликты: тот же `resultId` с другим payload — `RESULT_ID_CONFLICT`, другой `resultId` для challenge — `CHALLENGE_RESULT_EXISTS`.
- Версия `fitness-grpc-contracts` оставлена `0.1.2` до T002.5 по решению пользователя; потребители повторно не обновлялись.
- `sh ./mvnw -B -ntp -pl contracts/grpc-contracts verify`: `BUILD SUCCESS`, тесты 5/5, SpotBugs 0.
- `sh ./mvnw -B -ntp -pl services/challenge-service,services/coins-service -am clean test-compile`: `BUILD SUCCESS`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: 1004 ссылки, ошибок нет.
- Код сервисов, БД, Kafka, deployment, commit, push и deploy не выполнялись.

### 2026-11-17 — T002.4, Gateway OpenAPI

- `contracts/openapi/gateway-v1.yaml`: добавлены `$ref` на восемь путей `challenge-v1.yaml`, тег `Challenge`, защищённые пути и `upstream-errors` `503 → challenge_unavailable` для каждого пути. Схемы Challenge не дублируются.
- `contracts/gateway-v1.md`: добавлен раздел о маршрутах Challenge, forwarding, query string, публичном `503 challenge_unavailable` и недоступности внутреннего gRPC. Маршруты в Gateway ещё не реализованы.
- `/private/var/folders/.../opencode/openapi-venv/bin/python -m openapi_spec_validator contracts/openapi/challenge-v1.yaml contracts/openapi/gateway-v1.yaml`: оба файла `OK`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Код сервисов, маршруты Gateway, БД, Kafka, deployment, commit, push и deploy не выполнялись.

### 2026-11-17 — T002.5, проверка контрактов и потребителей

- `openapi_spec_validator` для `accounts-profile-v1.yaml`, `challenge-v1.yaml`, `coins-wallet-v1.yaml`, `gateway-v1.yaml`, `identity-auth-v1.yaml`: все пять `OK`.
- Все пять потребителей `fitness-grpc-contracts` используют `0.1.2`: identity, accounts, coins, gateway, challenge. Проверено скриптом по `pom.xml`.
- `sh ./mvnw -B -ntp -pl contracts/grpc-contracts verify`: `BUILD SUCCESS`, тесты 5/5, SpotBugs 0.
- `contracts/README.md`: в индексе присутствуют `coins-stake-v1.proto` и `challenge-results-v1.proto`; в команды проверки OpenAPI добавлен `challenge-v1.yaml`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Код сервисов, БД, Kafka, deployment, commit, push и deploy не выполнялись.

### 2026-11-17 — T003.1, обязательный upstream Challenge

- В `UpstreamId` добавлено значение `CHALLENGE`; в `UpstreamProperties` добавлена проверка `@AssertTrue`, требующая конфигурацию каждого известного upstream. Без записи `CHALLENGE` контекст Gateway не стартует с сообщением `Every known upstream must be configured`.
- `application.yaml` содержит `CHALLENGE.base-url: ${CHALLENGE_HTTP_BASE_URL}`; без переменной окружения старт также невозможен из-за неразрешённого placeholder.
- В `test-environment.properties` добавлен `CHALLENGE_HTTP_BASE_URL=http://127.0.0.1:1`, чтобы тесты запускались с обязательной конфигурацией.
- `GatewayPropertiesTest`: валидная конфигурация со всеми четырьмя upstream принимается; конфигурация без `CHALLENGE` отклоняется с ожидаемым сообщением; неполная карта даёт `isEveryUpstreamConfigured() == false`.
- `RouteValidationTest`: fixture полного набора upstream дополнен `CHALLENGE`; каталог маршрутов не менялся.
- `sh ./mvnw -B -ntp -pl services/gateway-service -am test`: `BUILD SUCCESS`, 50 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: `services/gateway-service/.env.example` и `deployment/env/gateway.env.example` ещё не содержат `CHALLENGE_HTTP_BASE_URL`; это задача T003.2. Маршруты Challenge ещё не добавлены (T003.3). БД, Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T003.2, конфигурация по средам

- `services/gateway-service/.env.example`: `CHALLENGE_HTTP_BASE_URL=http://127.0.0.1:8083`.
- `deployment/env/gateway.env.example`: `CHALLENGE_HTTP_BASE_URL=http://challenge-service:8083`.
- `application.yaml` (placeholder) и `test-environment.properties` (значение) уже содержат переменную после T003.1; проверены повторно.
- `services/gateway-service/README.md`: указан локальный адрес Challenge.
- Проверка grep: переменная присутствует во всех четырёх файлах.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Код, маршруты, БД, Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T003.3, маршруты Challenge в каталоге

- В `GatewayRouteCatalog` добавлены восемь маршрутов Challenge с `AUTHENTICATED`: drafts (`POST`), get (`GET`), draft (`PATCH`), publish/joins/cancel (`POST`), operations (`GET`), result (`GET`). Внутренний gRPC в каталог не входит.
- Тесты каталога адаптированы к шаблонным путям: конкретные UUID для запросов, ожидаемый `Allow` вычисляется по каталогу, `GatewayRouteValidator` и policy проверяют новые маршруты.
- `JwtValidationTest`: добавлен отдельный mock upstream Challenge; проверены forwarding и канонический `X-Request-Id` для восьми новых маршрутов; число вызовов и CheckSession обновлено.
- `contracts/gateway-v1.md`: статус раздела изменён на «маршруты зарегистрированы».
- `sh ./mvnw -B -ntp -pl services/gateway-service -am test`: `BUILD SUCCESS`, 50 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: табличная проверка каталога вместо `hasSize` и отдельные security-forwarding проверки остаются в T003.4; сквозная проверка динамических путей — T003.5. БД, Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T003.4, таблица каталога и security forwarding

- `RouteValidationTest`: ожидаемый каталог задан явной таблицей из 16 операций (метод, путь, upstream, доступ); проверка — `containsExactlyElementsOf`. `hasSize` для каталога больше не используется.
- `JwtValidationTest`: добавлен отдельный тест восьми маршрутов Challenge. Подтверждены `401` без токена и с невалидным JWT, `401` при неактивной сессии, `503 identity_unavailable` при недоступности CheckSession, forwarding исходного `Authorization` и канонического сгенерированного `X-Request-Id` вместо клиентского.
- Тест проверяет 8 успешных вызовов upstream Challenge и 24 обращения к CheckSession (по три на маршрут: неактивная, недоступная, активная сессия).
- `sh ./mvnw -B -ntp -pl services/gateway-service -am test`: `BUILD SUCCESS`, 51 тест, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: динамические пути через реальный прокси проверяются в T003.5; пограничный `GET /api/v1/challenges/drafts`, совпадающий с шаблоном `{challengeId}`, пока не ограничен UUID-паттерном. БД, Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T003.5, динамические пути через прокси

- `JwtValidationTest`: добавлена проверка `GET /api/v1/challenges/{challengeId}` и `GET /api/v1/challenge-operations/{operationId}` через реальный HTTP-прокси Gateway.
- Динамические сегменты маршрутов ограничены UUID-паттерном `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`. Поэтому `GET /api/v1/challenges/drafts` больше не совпадает с `{challengeId}`, получает `405 method_not_allowed` и не вызывает upstream. Добавлен тест `dynamicSegmentsRequireUuidPattern`.
- Mock upstream получает исходный конкретный путь с UUID; проверены статусы `200`, `404`, `409`, `422`, `503`, неизменённое тело, `Cache-Control: no-store`, исходный `Authorization` и канонический `X-Request-Id` вместо клиентского и upstream-значения.
- Query string на обоих динамических путях отклоняется с `400 invalid_request`; upstream не вызывается.
- `sh ./mvnw -B -ntp -pl services/gateway-service -am test`: `BUILD SUCCESS`, 53 теста, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: полный `verify` и SpotBugs выполняются в T003.6. БД, Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T003.6, verify Gateway

- `sh ./mvnw -B -ntp -pl services/gateway-service -am verify`: `BUILD SUCCESS`.
- Тесты: contracts 5/5, gateway 53/53; падений, ошибок и пропусков нет.
- SpotBugs: `BugInstance size is 0`, `Error size is 0`, предупреждений нет.
- Собран `gateway-service-0.1.3.jar`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: серверная выкладка и сквозной сценарий через Gateway не выполнялись; это T006. БД, Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T004.1, зависимости и конфигурация Challenge

- `services/challenge-service/pom.xml`: версия `0.1.3`; добавлены JPA, validation, Liquibase, PostgreSQL, MapStruct, gRPC (`grpc-netty-shaded` + контракты `0.1.2`), `spring-boot-starter-kafka`, Testcontainers PostgreSQL/JUnit/Kafka, `spring-boot-starter-webmvc-test`, BouncyCastle для тестовых сертификатов, Lombok и SpotBugs annotations.
- `application.yaml`: строгие настройки Jackson как в Accounts, datasource с Hikari, `ddl-auto: validate`, `open-in-view: false`, Liquibase changelog `classpath:db/changelog/db.changelog-master.yaml`.
- Добавлен `JacksonConfiguration` для запрета скалярных coercion.
- `db.changelog-master.yaml` пока пуст (`databaseChangeLog: []`); таблицы и changeSet появятся в T004.2.
- `ChallengeServiceApplicationTest` переведён на PostgreSQL Testcontainers с `disabledWithoutDocker = true`; тестовые ресурсы содержат безопасные DATABASE_* значения и отключённый Kafka health indicator.
- `.env.example` и `README.md` дополнены обязательными переменными БД.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 1/1; контекст стартует с Liquibase и JPA.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: таблиц и миграций нет, это T004.2 и требует отдельного разрешения на БД. Kafka-брокер в этой задаче не использовался; deploy и commit не выполнялись.

### 2026-11-17 — T004.2, миграции fitness_challenge

- Пользователь разрешил работу с локальной БД. Созданы роль `fitness_challenge_app` и база `fitness_challenge` с владельцем этой роли; пароль хранится только в игнорируемом `.env`.
- Liquibase changeset `001-create-challenge-tables` применён к пустой базе: `EXECUTED`.
- Созданы 12 таблиц из `plan.md`: `challenges`, `challenge_participants`, `challenge_operations`, `challenge_stake_sets`, `challenge_stake_set_items`, `challenge_release_intents`, `challenge_results`, `challenge_result_winners`, `challenge_settlement_status`, `challenge_outbox`, `challenge_inbox`, `challenge_idempotency_records`.
- CHECK соответствуют спеке: статусы, лимиты title/description/stake/duration, обязательные поля публикации, положительные суммы и версии, digest 32 байта, единственный `UNIQUE` результат на challenge, `UNIQUE(hold_id)` release intent, `UNIQUE(user_id, method, path, key)` идемпотентности.
- Частичный уникальный индекс `ux_challenge_operations_active` запрещает более одной незавершённой операции на `(challenge_id, user_id)`; завершённая операция не блокирует новую.
- Новый `ChallengeSchemaMigrationIntegrationTest` на Testcontainers проверяет список таблиц, CHECK публикации, уникальность release intent и частичный индекс операций.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 4/4.
- Проверка реальной базы: `\dt` показывает 12 таблиц challenge и таблицы Liquibase; `databasechangelog` содержит changeset `001-create-challenge-tables`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: rollback changeset не описан; доменный код и API ещё отсутствуют. Kafka, deploy и commit не выполнялись.

### 2026-11-17 — T004.3, доменные статусы и переходы

- `ChallengeStatus`: разрешены `DRAFT → OPEN`, `OPEN → ACTIVE`, `ACTIVE → FINISHED`, `OPEN → CANCELLED`. `FINISHED` и `CANCELLED` окончательны; `AWAITING_RESULTS` зарезервирован и не имеет переходов.
- `ParticipationStatus`: `JOINED → ACTIVE`, `ACTIVE → WINNER`, `ACTIVE → COMPLETED`; `WINNER` и `COMPLETED` окончательны. Отмена challenge не меняет статус участия.
- `InvalidStateTransitionException` содержит текущий и целевой статус без пользовательских данных; HTTP-маппинг появится вместе с обработчиком ошибок.
- Unit-тесты `ChallengeStatusTest` и `ParticipationStatusTest` проверяют разрешённые переходы, полную матрицу запретов, терминальные состояния и исключение.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 12/12.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: сериализация переходов пессимистической блокировкой строки и `version` относится к T004.4. БД, Kafka и deploy не менялись.

### 2026-11-17 — T004.4, репозитории и блокировки

- Добавлены JPA-сущности `ChallengeEntity`, `ChallengeParticipantEntity`, `ChallengeOperationEntity`, `ChallengeReleaseIntentEntity` и доменные enum для режима, видимости, старта, операций и release intent.
- `ChallengeEntity` хранит `version` и `next_join_sequence`; метод `takeNextJoinSequence` увеличивает счётчик и версию.
- `ChallengeRepository.findByIdForUpdate` использует нативный `SELECT ... FOR UPDATE`; добавлены нативные выборки участников, активных операций и release intent по hold. Enum-статусы передаются в SQL строкой.
- `ChallengeRepositoryIntegrationTest` на Testcontainers подтверждает:
  - два параллельных увеличения `next_join_sequence` дают номера 1 и 2, итоговое значение 3, версия 3 — без потерянного обновления;
  - версия увеличивается с 1 до 2 при обновлении под блокировкой;
  - частичный уникальный индекс отклоняет вторую незавершённую операцию и разрешает новую после `CONFIRMED`;
  - второй release intent для того же `hold_id` отклоняется, сохранённый остаётся с исходной причиной.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 16/16.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: сервисный слой и HTTP API ещё отсутствуют; блокировки проверены на Testcontainers PostgreSQL. БД, Kafka и deploy не менялись.

### 2026-11-17 — T004.5, claims-фильтр и security chain

- Добавлена зависимость `nimbus-jose-jwt` для разбора forwarded Bearer JWT.
- `ChallengeAuthenticationFilter` для путей `/api/v1/challenges*` и `/api/v1/challenge-operations*`: извлекает UUID из `sub`, известные роли из `roles`, кладёт `UsernamePasswordAuthenticationToken` в `SecurityContext` и очищает контекст в `finally`. Подпись, срок и сессию по-прежнему проверяет Gateway.
- `SecurityConfiguration`: stateless, CSRF/basic/form/logout отключены; Actuator health/info/prometheus доступны без токена, `/api/**` требует аутентификации, остальные пути запрещены.
- Unit-тесты фильтра: principal и роли, отсутствие/битый токен, не-UUID `sub`, неверный тип `roles`, пропуск Actuator и неизвестных путей, очистка контекста.
- Существующий тест приложения подтверждает доступность Actuator и закрытость `/unknown`.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 19/19.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: единый формат тела `401/403` и точный security handler — T004.6. Контроллеров API ещё нет; БД, Kafka и deploy не менялись.

### 2026-11-17 — T004.6, единый обработчик ошибок

- Добавлен `ApiErrorResponse` формата `{"error":{"code","message","details","requestId"}}`; `details` необязателен и по умолчанию пуст.
- `ApiExceptionHandler` сопоставляет:
  - `400 invalid_request` — некорректный JSON, UUID, отсутствующий заголовок или `InvalidRequestException`;
  - `401 unauthenticated` и `403 access_denied`;
  - `404` — `challenge_not_found`/`operation_not_found` через `ResourceNotFoundException`, неизвестный маршрут — `not_found`;
  - `409` — код из `BusinessConflictException` (`version_conflict`, `challenge_full` и т.д.);
  - `422 validation_error` — поля, параметры, нарушения состояния с `details`;
  - `503 service_unavailable` — `DataAccessException`/`TransactionSystemException` без внутренних подробностей;
  - `500 internal_error` — только класс исключения в логе, без текста и данных.
- `SecurityExceptionHandler` передаёт `AuthenticationException`/`AccessDeniedException` в общий resolver; security chain использует его как entry point и access denied handler.
- `X-Request-Id` из `RequestIdFilter` попадает в тело ошибки; все ответы ошибок получают `Cache-Control: no-store`.
- Тесты: 8 unit-проверок маппинга; интеграционный тест приложения проверяет `401 unauthenticated` с `requestId` для `/api/**`, закрытые `/unknown` и `/actuator/env`, доступность health/prometheus и `404 not_found` для валидного forwarded токена.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 27/27.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: конкретные бизнес-коды будут бросать сервисы в T004.7–T004.12; HTTP-контроллеров пока нет. БД, Kafka и deploy не менялись.

### 2026-11-17 — T004.7, хранение HTTP-идемпотентности

- `IdempotencyStore` использует нативные SQL-запросы через `NamedParameterJdbcTemplate`; тело и заголовки ответа пишутся в JSONB через `CAST(... AS jsonb)`.
- Ключ привязан к `(user_id, method, path, key)`: тот же ключ на другом пути или у другого пользователя — отдельная запись.
- `IdempotencyService.requireKey` принимает только UUID; пустой или невалидный ключ — `400 invalid_request`.
- Отпечаток тела — SHA-256 от исходных UTF-8 байтов. Повтор с тем же отпечатком возвращает сохранённый статус, тело и заголовки; другой отпечаток даёт `409 idempotency_key_conflict`.
- Уникальность `(user_id, method, path, key)` проверена: повторная вставка отклоняется `DuplicateKeyException`.
- `IdempotencyServiceIntegrationTest` на Testcontainers покрывает валидацию ключа, replay, конфликт тела и изоляцию по пути/пользователю.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 30/30.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: подключение к draft/publish/join endpoints — T004.8; стратегия повторного чтения после конкурентной вставки будет реализована там. БД, Kafka и deploy не менялись.

### 2026-11-17 — T004.8, API черновиков и чтения

- DTO: `CreateDraftRequest`, `UpdateDraftRequest` с различением отсутствующего и явно null поля, `DraftResponse`, `ChallengeDetailsResponse`, `OperationResponse`.
- `ChallengeDraftService`: создание и изменение черновика под `findByIdForUpdate`, проверка владельца, состояния `DRAFT` и `version`; чтение challenge с `viewer`, `refundStatus` и `settlementStatus`; чтение операции только её владельцем.
- `ChallengeReadRepository` вычисляет `settlementStatus` по `challenge_results`/`challenge_settlement_status`: `UNKNOWN` без результата, `PENDING` до расчёта, `COMPLETED` после.
- `refundStatus`: `NONE` вне отмены, `PENDING` при незавершённых release intent, `COMPLETED` после подтверждения.
- `ChallengeController`: `POST /challenges/drafts` (`201` + `Location`), `PATCH /challenges/{id}/draft` (`200`), `GET /challenges/{id}`, `GET /challenge-operations/{id}`; везде `Cache-Control: no-store`.
- Идемпотентность подключена к POST/PATCH: повтор с тем же ключом и каноническим телом возвращает сохранённый ответ, другое тело — `409 idempotency_key_conflict`.
- `ChallengeDraftFlowIntegrationTest` подтверждает: `201`/`Location`, replay, `400` без ключа, `200` после PATCH, `409 version_conflict`, `422 validation_error`, `409` идемпотентности, `404` для чужого черновика и чужой операции, корректные `refundStatus`/`settlementStatus`, `viewer.isCreator`.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 33/33.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: publish/join/result endpoints появятся в T004.10–T004.16; результат PENDING-операции пока null. БД, Kafka и deploy не менялись.

### 2026-11-17 — T005.1, миграции Coins

- Созданы роль `fitness_coins_app` и база `fitness_coins`; пароль хранится только в локальном окружении и не добавлен в Git.
- Liquibase changeset `002-create-stake-and-settlement-tables` применён к пустой базе: `EXECUTED`.
- Созданы таблицы: `coin_holds`, `coin_ledger_entries`, `coin_stake_sets`, `coin_stake_operations`, `coin_stake_seal_uploads`, `coin_stake_seal_batches`, `coin_stake_seal_items`, `coin_sealed_stakes`, `coin_settlements`, `coin_settlement_winners`, `coin_settlement_allocations`, `coin_settlement_hold_moves`, `coin_inbox`, `coin_outbox`.
- Staging-таблицы seal добавлены для протокола Begin/Put/Finalize (T005.4); план перечисляет их косвенно через `sealed stakes`.
- Ограничения: активный hold один на `(challenge_id, user_id)`; уникальность `join_operation_id`; пары state/version/digest набора; каталог операций и статусов; аренда и фазы settlement; JSONB result/payload; digest/hash 32 байта.
- `coin_wallets` не изменена: колонки `user_id`, `available_balance`, `locked_balance`, `created_at`, `updated_at` совпадают с исходной миграцией.
- Новый `CoinsSchemaMigrationIntegrationTest` на Testcontainers проверяет список таблиц, неизменность `coin_wallets`, запрет второго активного hold и CHECK состояния набора.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, 50 тестов, 0 ошибок.
- Проверка реальной базы: `databasechangelog` содержит changesets 001 и 002; `\dt` показывает 14 новых таблиц и `coin_wallets`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: доменный код Coins ещё отсутствует; production-миграции не выполнялись. Kafka и deploy не менялись.

### 2026-11-17 — T005.2, резервирование ставки

- `StakeReservationService` выполняет reserve одной транзакцией: блокирует строку `coin_stake_sets`, проверяет сохранённую операцию, активный hold и кошелёк.
- При успехе создаёт `coin_holds.ACTIVE`, переносит сумму из `available` в `locked`, пишет одну ledger-запись `RESERVE` с `available_delta = -amount`, `locked_delta = +amount` и сохраняет исход операции в `coin_stake_operations` (JSONB).
- Идемпотентность: тот же `(kind, id)` и request hash возвращает сохранённый результат; другой payload — `OperationIdConflictException`.
- Исходы: `RESERVED` с `holdId`, `INSUFFICIENT_FUNDS`, `ACTIVE_HOLD_EXISTS`; отсутствие кошелька — `WalletNotFoundException` (gRPC-маппинг будет в T004.9).
- `StakeRepository` использует явные SQL-запросы и `NamedParameterJdbcTemplate`; время строк задаёт PostgreSQL.
- `ReserveStakeServiceIntegrationTest` на Testcontainers проверяет атомарность, replay, конфликт operation ID, недостаток средств, активный hold и отсутствующий кошелёк.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, 55 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: gRPC-метод `ReserveStake` и маппинг ошибок появятся в T004.9; seal/freeze — T005.4–T005.5. Kafka и deploy не менялись.

### 2026-11-17 — T005.3, освобождение ставки

- `ReleaseStakeService` под блокировкой набора и hold переводит hold в `RELEASED`, возвращает сумму из `locked` в `available`, пишет ledger `RELEASE` (`available_delta = +amount`, `locked_delta = -amount`) и сохраняет исход операции.
- Повтор с тем же release operation ID и payload возвращает сохранённый результат без второй проводки; другой payload — `OperationIdConflictException`.
- Другой operation ID для уже освобождённого или распределённого hold — `HoldNotReleasableException`; hold другого challenge — `HoldChallengeMismatchException`; неизвестный hold — `HoldNotFoundException`.
- `StakeRepository` дополнен явными SQL-запросами `lockHold`, `markHoldReleased`, `insertReleaseLedger` и общим `insertOperation`.
- `ReleaseStakeServiceIntegrationTest` на Testcontainers проверяет одноразовость, replay, второй release, чужой challenge, неизвестный hold и конфликт причины.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, 60 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: gRPC-метод `ReleaseStake` — T004.9; freeze/complete — T005.5. Kafka и deploy не менялись.

### 2026-11-17 — T005.4, пакетный seal набора

- `StakeSetService` реализует `BeginStakeSet`, `PutStakeBatch` и `FinalizeStakeSet` по контракту: Begin сохраняет ожидаемые count/digest, Put принимает пакеты 1–500, Finalize сверяет digest, count и активные holds.
- Digest считается по строкам `hold_id,user_id,amount`, отсортированным по строковому `hold_id`, через `\n` без конечного перевода; проверяется 64 lowercase hex символа.
- Все операции сериализуются блокировкой `coin_stake_sets`; повтор Begin/Put/Finalize безопасен, изменённый payload — `OperationIdConflictException`.
- Новый reserve после `SEALED` запрещён (`StakeSetSealedException`); в `CANCEL_DRAINING`/`CANCELLED` — `StakeSetCancelledException`.
- Дубли hold/user между пакетами отклоняются `DuplicateStakeEntryException`; несовпадение состава — `StakeSetMismatchException`; неполный набор — `StakeSetIncompleteException`.
- `StakeSetServiceIntegrationTest` на Testcontainers покрывает успешный seal, replay, блокировку нового reserve, неполный набор, расхождение с holds, дубли/изменение пакета, повтор Begin и параллельный seal, размер пакета.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, 66 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: gRPC-методы seal — T004.9; freeze/complete — T005.5. Kafka и deploy не менялись.

### 2026-11-17 — T005.5, отмена набора ставок

- `StakeCancellationService.freeze` переводит набор из `OPEN`/`SEALED` в `CANCEL_DRAINING`, сохраняет `cancellation_id` и блокирует новые reserve; после `SETTLED` возвращает `StakeSetSettledException`.
- `complete` требует `CANCEL_DRAINING` с тем же `cancellation_id`, проверяет отсутствие активных holds и фиксирует `CANCELLED`.
- Повтор freeze/complete с теми же ID возвращает сохранённый результат; другой cancellation ID — `OperationIdConflictException`; complete без freeze — `StakeSetCancellationConflictException`; активные holds — `ActiveHoldsRemainException`.
- `StakeRepository` дополнен `lockStakeSetSnapshot`, `markStakeSetDraining`, `markStakeSetCancelled`, `countActiveHolds`.
- В `tasks.md` добавлена T005.10 — gRPC-сервер ставок Coins (включая `GetStakeOperation`); T004.9 теперь зависит от неё.
- `StakeCancellationServiceIntegrationTest` на Testcontainers покрывает freeze/release/complete, блокировку нового reserve, активный hold, идемпотентность, чужой cancellation ID и complete без freeze.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, 70 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: gRPC-сервер ставок — T005.10; Kafka, settlement и deploy не менялись.

### 2026-11-17 — T005.10, gRPC-сервер ставок

- `StakeGrpcService` публикует все 8 RPC контракта `coins-stake-v1.proto`: reserve/release, get operation, begin/put/finalize, freeze/complete.
- `GetStakeOperation` читает сохранённый исход RESERVE/RELEASE через `StakeOperationQueryService`; неизвестная операция — `NOT_FOUND`.
- `ChallengeAuthorizationInterceptor` разрешает методы только сертификату с SAN `spiffe://fitness.local/challenge-service`; подключён в `SecureGrpcServer` рядом с существующим Identity interceptor.
- `StakeGrpcErrors` возвращает rich gRPC Status с `google.rpc.ErrorInfo` (`domain = coins.fitness.internal`): `WALLET_NOT_FOUND`, `HOLD_NOT_FOUND`, `OPERATION_NOT_FOUND`, `OPERATION_ID_CONFLICT`, `HOLD_NOT_RELEASABLE`, `HOLD_CHALLENGE_MISMATCH`, `STAKE_SET_*`, `ACTIVE_HOLDS_REMAIN`, `CANCELLATION_CONFLICT`, `DUPLICATE_STAKE_ENTRY`, `COINS_UNAVAILABLE`, `INTERNAL_ERROR`.
- Добавлена зависимость `proto-google-common-protos` 2.64.1; причины `CANCELLATION_CONFLICT` и `DUPLICATE_STAKE_ENTRY` отражены в комментарии контракта.
- Тесты: маппинг reserve/release/get operation и rich errors; SAN-проверка challenge/identity; end-to-end mTLS — challenge-сертификат получает `RESERVED`, identity-сертификат получает `PERMISSION_DENIED`.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, 78 тестов, 0 ошибок.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: клиент Challenge — T004.9; settlement/Kafka не менялись.

### 2026-11-17 — T004.9, gRPC-клиент Coins

- Добавлены `CoinsStakeProperties` и `CoinsStakeConfiguration`; параметры `COINS_STAKE_GRPC_*`, `CHALLENGE_GRPC_*`; deadline зафиксирован 3s и валидируется как меньше gateway 7s.
- `CoinsStakeClient` создаёт mTLS-канал лениво и публикует все 8 stake-методов контракта.
- `UNAVAILABLE` и `DEADLINE_EXCEEDED` классифицируются как `CoinsOutcomeUnknownException`; клиент повторяет вызов один раз с тем же operation ID. `ALREADY_EXISTS/OPERATION_ID_CONFLICT` — `CoinsOperationConflictException`; остальные постоянные ошибки — `CoinsRejectedException` с gRPC code и reason.
- `.env.example` и тестовые свойства дополнены обязательными адресами и путями сертификатов.
- `CoinsStakeClientTest` проверяет deadline, успешный ответ, повтор при UNAVAILABLE, отсутствие повтора при постоянной ошибке, конфликт operation ID и отказ от deadline >= 7s.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 38/38.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: mTLS-канал клиента ещё не проверен end-to-end с реальным Coins; это T006.2. Клиент пока не подключён к publish/join. БД, Kafka и deploy не менялись.

### 2026-11-17 — T004.10, публикация челленджа

- `ChallengePublishService` сначала в отдельной транзакции фиксирует durable `PUBLISH`-операцию, затем вне транзакции вызывает `ReserveStake` Coins, затем отдельной транзакцией подтверждает участие создателя и переводит challenge в `OPEN`.
- Успех: `200`, создатель `JOINED`, hold сохранён в операции, версия challenge увеличена.
- Недостаток монет и активный hold: `409 insufficient_coins` / `409 stake_conflict`, challenge остаётся `DRAFT`, операция `FAILED`.
- Неизвестный исход Coins: `202` с `operationId` и `statusUrl`, операция остаётся `PENDING`.
- Неверная версия — `409 version_conflict`; чужой черновик — `404 challenge_not_found`; незаполненные поля — `422 validation_error` с `details`.
- Идемпотентность: повтор publish с тем же ключом возвращает сохранённый `200`/`202` без второго резерва.
- `ChallengePublishFlowIntegrationTest` на Testcontainers с mock `CoinsStakeClient` покрывает успех/replay, insufficient_coins, stake_conflict, неизвестный исход, stale version, чужого создателя и незаполненный черновик.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 43/43.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: recovery worker для `PENDING` и release hold при сбое подтверждения — T004.13; Kafka и deploy не менялись.

### 2026-11-17 — T004.11, вступление в челлендж

- `ChallengeJoinService` сначала сохраняет durable `JOIN`-операцию и вызывает `ReserveStake` Coins; место проверяется только после подтверждённого резерва, под блокировкой строки challenge.
- При подтверждении атомарно резервируется `next_join_sequence`, создаётся участие `JOINED`, операция фиксируется `CONFIRMED`.
- Ошибки: `already_joined`, `challenge_full`, `challenge_started`, `challenge_cancelled`, `insufficient_coins`, `stake_conflict`; чужой/неопубликованный черновик — `404`.
- Если после резерва место уже занято или состояние изменилось, вызывается `ReleaseStake` с причиной `JOIN_NOT_CONFIRMED`; окончательный `409` возвращается только после успешного release, иначе `202`.
- Неизвестный исход Coins оставляет операцию `PENDING` и возвращает `202`.
- `ChallengeJoinFlowIntegrationTest` покрывает успех/replay, повторное вступление и состояния, бизнес-исходы Coins, `202` и release при занятом месте.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 48/48.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: гонка двух join за последнее место — T004.20; durable release intent — T004.12. Kafka и deploy не менялись.

### 2026-11-17 — T004.12, отмена и release intents

- `ChallengeCancelService` отменяет только создатель и только при отсутствии других подтверждённых участников; иначе `409 cancellation_not_allowed`, чужой challenge — `404`.
- В одной транзакции фиксируются `CANCELLED` и один durable release intent на hold создателя (`UNIQUE(hold_id)`), повтор использует существующий intent.
- После commit выполняются `FreezeStakeSet` → `ReleaseStake (CHALLENGE_CANCELLED)` → `CompleteStakeCancellation`; intent подтверждается только после успешного release.
- `200` возвращается с `refundStatus COMPLETED` после подтверждённого release; при неизвестном исходе — `PENDING`, challenge остаётся `CANCELLED`, recovery продолжит позже.
- Повтор с тем же `Idempotency-Key` возвращает сохранённый ответ; новый ключ для уже отменённого challenge возвращает текущий подтверждённый refund без повторных вызовов Coins.
- `ChallengeCancelFlowIntegrationTest` покрывает успешную отмену/replay, отмену с другим участником, чужого создателя и `PENDING` при неизвестном ответе Coins.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 52/52.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: recovery для `PENDING` release/complete — T004.13; Kafka и deploy не менялись.

### 2026-11-17 — T004.13, recovery worker

- Миграция `003-add-recovery-leases`: `lease_until`/`lease_generation` у операций, release intents и stake sets; индексы recovery. Changeset применён к локальной `fitness_challenge`.
- `ChallengeRecoveryRepository` захватывает строки через `FOR UPDATE SKIP LOCKED`, продлевает lease и увеличивает поколение; worker очищает lease после обработки.
- `ChallengeRecoveryWorker` по расписанию (`challenge.recovery.fixed-delay`) продолжает:
  - PUBLISH/JOIN через `GetStakeOperation(RESERVE)` и повторное подтверждение;
  - release intent через `GetStakeOperation(RELEASE)` или повтор freeze/release/complete.
- Hold не освобождается по таймеру: release выполняется только по сохранённому intent или при невозможности подтвердить место.
- Неизвестный исход оставляет операцию/intent в `PENDING`; `NOT_FOUND` не считается отказом.
- `ChallengeRecoveryWorkerIntegrationTest` проверяет восстановление publish после `202` и release intent после неизвестного ответа.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 54/54.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: восстановление seal появится вместе с T004.14; multi-instance lease-поведение проверяется в T004.20. Kafka и deploy не менялись.

### 2026-11-17 — T004.14, seal и WHEN_FULL

- `ChallengeSealService` после второго подтверждённого участия сохраняет снимок `challenge_stake_sets`/`challenge_stake_set_items` с версией и стабильным `sealOperationId`.
- После commit выполняются `BeginStakeSet` → `PutStakeBatch` (один пакет) → `FinalizeStakeSet`; только после подтверждённого finalize challenge и участники становятся `ACTIVE`, набор — `SEALED`.
- До подтверждённого seal challenge остаётся `OPEN`; повторный вызов не создаёт второй набор и использует тот же `sealOperationId`.
- `ChallengeSealRepository` хранит снимок и выдаёт collecting-наборы recovery worker; `ChallengeRecoveryWorker` продолжает незавершённый seal.
- Digest набора считается по `hold_id,user_id,amount`, отсортированным по строковому hold_id.
- `ChallengeSealFlowIntegrationTest` проверяет `OPEN` до seal, `ACTIVE` после, статусы участников, повтор без второго begin и восстановление collecting-набора после неизвестного ответа.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 56/56.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: гонка cancel/seal и multi-instance lease — T004.20. Kafka и deploy не менялись.

### 2026-11-17 — T004.15, outbox publisher

- `ChallengeOutboxService` пишет событие с `Propagation.MANDATORY`: локальное изменение и outbox фиксируются одной транзакцией.
- `ChallengeOutboxRepository` выполняет claim через `FOR UPDATE SKIP LOCKED`, lease и `lease_generation`; `markPublished` вызывается только после ack.
- `ChallengeOutboxPublisher` публикует пачками, очищает lease при ошибке и не удаляет событие; повтор после потери ack возможен, потребитель дедуплицирует по `event_id`.
- `KafkaChallengeEventPublisher` публикует в `fitness.<event_type>.v1` с ключом `aggregateId`; Kafka-конфигурация добавлена в `application.yaml` и окружения.
- Добавлен gauge `challenge_outbox_backlog`.
- `ChallengeOutboxIntegrationTest` проверяет atomic commit/rollback, публикацию ровно один раз, `published_at` и отсутствие повторной публикации.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 58/58.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: событие `challenge.finished` формируется в T004.16; реальный Kafka-брокер в тестах не поднимался. Deploy не менялся.

### 2026-11-17 — T004.16, gRPC-результат

- `ChallengeResultService.finalizeResult` одной транзакцией сохраняет `challenge_results`, победителей, winner digest, статусы участия, `FINISHED` и outbox `challenge.finished`.
- `SINGLE_WINNER` требует одного победителя, `DRAW` — двух; `MULTIPLE_WINNERS` отклоняется `UNSUPPORTED_OUTCOME`; победители проверяются по участию и версии/digest закрытого набора.
- Повтор по `resultId` с тем же payload возвращает сохранённый результат; другой payload — `RESULT_ID_CONFLICT`; другой result для challenge — `CHALLENGE_RESULT_EXISTS`.
- `ListResultWinners` отдаёт победителей страницами; неизвестный результат — `RESULT_NOT_FOUND`.
- `ChallengeResultGrpcService` публикует оба метода и маппит ошибки в gRPC `Status`/`ErrorInfo` (`challenge.fitness.internal`).
- `ChallengeResultGrpcServer` включается свойством `challenge.result.grpc.enabled`, использует mTLS и проверяет SAN источника результата и `coins-service`; в тестах сервер отключён.
- `ChallengeResultFlowIntegrationTest` покрывает SINGLE_WINNER/replay, DRAW, UNSUPPORTED_OUTCOME, RESULT_ID_CONFLICT, неверный набор, чужого победителя и `ListResultWinners`.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 62/62.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: mTLS-сервер результата не проверен end-to-end; курсор списка победителей в MVP — UUID без HMAC. Kafka и deploy не менялись.

### 2026-11-17 — T004.17, consumer `coins.settlement_completed`

- `SettlementCompletedConsumer` подписан на `fitness.coins.settlement_completed.v1` с группой `challenge-service.settlement-completed`; key проверяется как `challengeId`.
- `SettlementCompletedProcessor` разбирает snake_case-оболочку и camelCase `data`, проверяет `event_type`, `schema_version=1`, `producer=coins`, обязательные поля, версию/ruleVersion и соответствие окончательному результату.
- Проекция `COMPLETED` и запись `challenge_inbox` фиксируются одной транзакцией; digest считается по исходным UTF-8 байтам payload.
- Повтор того же `event_id` с тем же digest — `DUPLICATE` без изменения состояния; другой digest — `QUARANTINED`, проекция не меняется.
- Другой `settlementId` для уже рассчитанного challenge не перезаписывает проекцию; неизвестный результат пробрасывает `SettlementNotReadyException`, inbox не отмечается применённым.
- `SettlementCompletedConsumerIntegrationTest` покрывает применение и replay, конфликт digest, другой `settlementId`, неизвестный результат и несовпадение версии набора.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am test`: `BUILD SUCCESS`, contracts 5/5, challenge 67/67.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: таблица `challenge_pending_events` не входит в MVP; конфликт digest фиксируется отказом от применения и ERROR-логом, а неизвестный результат удерживается повторной доставкой Kafka. Реальный брокер в тестах не поднимался; deploy не менялся.

### 2026-11-17 — T005.6, settlement Coins

- `ChallengeFinishedConsumer` подписан на `fitness.challenge.finished.v1` с группой `coins-service.challenge-finished`; key проверяется как `challengeId`.
- `ChallengeFinishedProcessor` разбирает snake_case-оболочку и camelCase `data`, проверяет обязательные поля, outcome/count, digest-формат и inbox по `event_id`.
- `ChallengeResultGrpcClient` читает победителей страницами по mTLS; `UNAVAILABLE`/`DEADLINE_EXCEEDED` оставляют событие на повтор, постоянный отказ уходит в карантин.
- `SettlementService` одной транзакцией проверяет закрытый набор, `setDigest`, count/digest победителей, принадлежность победителей набору и точное совпадение активных holds; затем переводит holds в `SETTLED`, обновляет кошельки и ledger, сохраняет allocations/hold moves, переводит stake set в `SETTLED`, settlement — в `COMPLETED` и пишет `coins.settlement_completed` в outbox вместе с inbox.
- `SINGLE_WINNER` начисляет победителю весь банк; `DRAW` возвращает каждому победителю его ставку. Сумма выплат равна сумме holds; остатка нет.
- Повтор события и другой `event_id` для того же `resultId` не создают второй settlement и не платят дважды; конфликт digest и несовпадение результата не изменяют состояние.
- Исправлен выявленный дефект T005.4: `FinalizeStakeSet` теперь сохраняет `coin_sealed_stakes` — неизменяемый снимок, необходимый для расчёта.
- `SettlementServiceIntegrationTest` покрывает SINGLE_WINNER, DRAW, несовпадение digest/состава победителей, неверный `setDigest`, replay события и бизнес-повтор по `resultId`.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, contracts 5/5, coins 85/85.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: mTLS-клиент Challenge не проверен end-to-end; реальный Kafka-брокер и публикация outbox появятся в T005.7. Escrow, pending/quarantine-таблицы и административное возобновление не входят в MVP. Deploy не менялся.

### 2026-11-17 — T005.7, outbox publisher Coins

- `CoinOutboxRepository` выполняет claim через `FOR UPDATE SKIP LOCKED`, lease, `lease_generation`, `markPublished` только после ack и `clearLease` при ошибке.
- `CoinOutboxPublisher` публикует пачками; событие не удаляется и при ошибке остаётся доступным после истечения lease.
- `KafkaCoinEventPublisher` публикует в `fitness.<event_type>.v1` с ключом `aggregateId`; для расчёта это `fitness.coins.settlement_completed.v1` и `challengeId`.
- Добавлены producer-настройки Kafka, `@EnableScheduling` и gauge `coins_outbox_backlog`; в тестах планировщик отключён через `coins.outbox.fixed-delay=1h`.
- `CoinOutboxIntegrationTest` проверяет публикацию ровно один раз, `published_at`, lease без параллельного claim, повторный claim после `clearLease` и сохранение события при ошибке публикации.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, contracts 5/5, coins 88/88.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: реальный Kafka-брокер не поднимался; тест publisher использует mock `CoinEventPublisher`. Сквозная публикация и redelivery проверяются в T005.8/T006.1. Deploy не менялся.

### 2026-11-17 — T005.8, тесты Coins

- `StakeConcurrencyIntegrationTest` проверяет конкурентные сценарии:
  - два release одного hold с разными operation ID: ровно один `RELEASED`, второй — `HoldNotReleasableException`, баланс восстановлен один раз;
  - reserve третьего участника одновременно с `FinalizeStakeSet`: либо набор `SEALED` и reserve отклонён, либо reserve принят, а finalize отклонён по `StakeSetMismatchException`; промежуточного состояния нет;
  - freeze набора одновременно с reserve: freeze всегда фиксирует `CANCEL_DRAINING`, reserve либо принят до freeze, либо отклонён.
- Потерянные ответы покрыты существующими replay-тестами reserve, release, begin/put/finalize: повтор с тем же operation ID возвращает сохранённый результат и не создаёт вторую проводку.
- Идемпотентность settlement и конфликт digest покрыты `SettlementServiceIntegrationTest`: повтор события, другой `event_id` для того же `resultId`, конфликт digest и отсутствие повторной выплаты.
- `sh ./mvnw -B -ntp -pl services/coins-service -am test`: `BUILD SUCCESS`, contracts 5/5, coins 91/91.
- `StakeConcurrencyIntegrationTest` повторён дважды подряд: 3/3 в каждом запуске.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: тесты используют PostgreSQL Testcontainers; реальный Kafka-брокер и сетевые сбои не имитируются. Конкурентные сценарии проверяют один из допустимых порядков блокировок, а не все возможные тайминги. Deploy не менялся.

### 2026-11-17 — T004.18–T004.20, тесты Challenge

- T004.18: `ChallengeRepositoryIntegrationTest` покрывает pessimistic lock и `next_join_sequence`, увеличение `version`, частичный уникальный индекс активной операции и уникальность release intent на hold; `ChallengeOutboxIntegrationTest` — atomic commit/rollback, claim, lease, повтор и `published_at`.
- T004.19: существующие flow-тесты покрывают `200/201/202/404/409/422`, идемпотентность, чужой draft и operation. Добавлен `ChallengeWebSecurityIntegrationTest`:
  - `401` без токена с общим форматом ошибки;
  - `400` для некорректного UUID в пути;
  - `503` при недоступности хранилища без внутренних подробностей;
  - `requestId` совпадает с `X-Request-Id` и присутствует в теле.
- T004.20: добавлен `ChallengeConcurrencyIntegrationTest`:
  - два одновременных join за последнее место: ровно один `200`, второй `409 challenge_full`, участников ровно двое;
  - одновременные join и cancel: либо cancel `200` и join `409 challenge_cancelled`, либо join `200` и cancel `409 cancellation_not_allowed`; финальное состояние согласовано.
- Потерянные ответы Coins до/после commit, восстановление publish/release/seal после рестарта worker и повтор seal покрыты `ChallengeRecoveryWorkerIntegrationTest`, `ChallengeSealFlowIntegrationTest`, `ChallengeJoinFlowIntegrationTest` и `ChallengeCancelFlowIntegrationTest`.
- `ChallengeConcurrencyIntegrationTest` повторён трижды подряд: 2/2 в каждом запуске.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок Python-скриптом: ошибок нет.
- Ограничение: конкурентные HTTP-тесты используют PostgreSQL Testcontainers и mock Coins; проверяется один из допустимых порядков блокировок, не все тайминги. Deploy не менялся.

### 2026-11-17 — T004.21, verify challenge-service

- `sh ./mvnw -B -ntp -pl services/challenge-service -am verify`: `BUILD SUCCESS`, contracts 5/5, challenge 72/72.
- SpotBugs: 0 bugs. Исправлено замечание `EI_EXPOSE_REP` в `ResultWinnersPage`: конструктор и accessor возвращают защитную копию списка.
- Ограничение: mTLS-сервер результата и реальный Kafka-брокер по-прежнему не проверены end-to-end. Deploy не менялся.

### 2026-11-17 — T005.9, verify coins-service

- `sh ./mvnw -B -ntp -pl services/coins-service -am verify`: `BUILD SUCCESS`, contracts 5/5, coins 91/91.
- SpotBugs: 0 bugs.
- Ограничение: реальный Kafka-брокер и mTLS-клиент Challenge не проверены end-to-end; они входят в сквозной сценарий T006. Deploy не менялся.

### 2026-11-17 — T006.1, локальная Kafka и подготовка данных

- Добавлен `deployment/compose.local-kafka.yaml`: один broker/controller в KRaft, без ZooKeeper и без PostgreSQL; production `compose.yaml`, release scripts и секреты не менялись.
- `docker compose -f deployment/compose.local-kafka.yaml config --quiet`: успешно.
- `docker compose -f deployment/compose.local-kafka.yaml up -d`: контейнер `fitness-local-kafka` запущен и перешёл в `healthy`.
- Созданы топики `fitness.challenge.finished.v1` и `fitness.coins.settlement_completed.v1` (3 партиции, replication factor 1); `kafka-topics.sh --list` подтверждает оба имени.
- Добавлен `scripts/prepare-local-challenge-data.sh`: регистрирует/логинит двух пользователей через Identity, получает две сессии, идемпотентно пополняет два кошелька Coins через локальный ledger и пишет токены только в игнорируемый `secrets/challenge-local-test-data.env`.
- `bash -n scripts/prepare-local-challenge-data.sh`: успешно. SQL-пополнение проверено на временном кошельке: повтор не увеличивает баланс второй раз.
- В `deployment/README.md` добавлена локальная инструкция; production-раздел не менялся.
- По решению пользователя созданы локальные БД `fitness_identity`, `fitness_accounts`, роли приложений и `.env` для Identity, Accounts, Coins, Gateway и Challenge; пароли и токены находятся только в игнорируемых файлах.
- Для локального окружения созданы mTLS-сертификаты и ES256 JWT-ключи в `secrets/`; mTLS-сертификаты переведены на RSA, потому что LibreSSL создавал EC-сертификаты с явными параметрами, которые JDK не принимает.
- Исправлен конфликт портов: Kafka перенесена на `localhost:29092`, потому что Coins gRPC занимает `9092`; обновлены `deployment/compose.local-kafka.yaml`, `.env.example` Coins/Challenge и локальные `.env`.
- Запущены Accounts, Coins, Identity и Gateway; `readiness` всех четырёх сервисов — `200`.
- `scripts/prepare-local-challenge-data.sh` выполнен: созданы два пользователя, две сессии и два кошелька с балансом 20.
- Обе сессии проверены через Gateway: `GET /api/v1/coins/me` вернул `200` и `{"availableBalance":20,"lockedBalance":0}` для каждого пользователя.
- Kafka остаётся `healthy`; топики присутствуют. Секреты, пароли и токены в Git не добавлены.
- Ограничение: локальный контур использует тестовую накачку кошельков через ledger с типом `SETTLEMENT`, а не пользовательское начисление; это только тестовые данные. Deploy и production Compose не менялись.

### 2026-11-17 — T006.2, publish и join через Gateway

- Запущен Challenge на `127.0.0.1:8083`; readiness — `200`. Challenge использует локальный Kafka `localhost:29092`, gRPC Coins `localhost:9092` и mTLS-сертификат `challenge-client`.
- Сессии обновлены через `scripts/prepare-local-challenge-data.sh`; баланс каждого кошелька остался 20.
- Через Gateway `POST /api/v1/challenges/drafts` → `201`, `PATCH .../draft` → `200` (`version=2`), `POST .../publish` → `200`, статус `OPEN`, создатель `JOINED`.
- После publish кошелёк создателя: `availableBalance=10`, `lockedBalance=10`; в Coins создан hold `ACTIVE` на 10.
- `POST /api/v1/challenges/{id}/joins` вторым пользователем → `200`, участие `JOINED`.
- После join кошелёк второго участника: `availableBalance=10`, `lockedBalance=10`; в Coins два hold `ACTIVE` по 10.
- В Coins `coin_stake_sets.state=SEALED`, `set_version=1`, в `coin_sealed_stakes` две строки.
- `GET /api/v1/challenges/{id}` через Gateway: `status=ACTIVE`, `participants.joined=2`, у создателя `participationStatus=ACTIVE`.
- Ограничение: проверен успешный путь `200`; ветка `202` при неизвестном ответе Coins проверяется в T006.6. Deploy не менялся.

### 2026-11-17 — T006.3, гонка за последнее место

- Локальный скрипт подготовки расширен до трёх пользователей: третий пользователь создаёт challenge, два других одновременно претендуют на последнее место.
- Через Gateway создан, опубликован и открыт новый challenge; создатель получил hold на 10.
- Два параллельных `POST .../joins` отправлены одновременно; результаты: один `200 JOINED`, второй `409 challenge_full`.
- В `challenge_participants` ровно два участника: создатель и победитель гонки.
- В Coins: три `RESERVE` (создатель и оба претендента), один `RELEASE`, два `ACTIVE` hold и один `RELEASED` hold; `coin_stake_sets.state=SEALED`.
- Повтор join победителя с сохранённым `Idempotency-Key`: `200` и сохранённый ответ; количество `RESERVE` осталось 3, второй hold не создан.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок: ошибок нет.
- Ограничение: гонка проверена в одном локальном запуске; массовые конкурентные нагрузки и реальный брокер под нагрузкой не проверялись. Deploy не менялся.

### 2026-11-17 — T006.4, отмена и возврат

- Обычная отмена через Gateway: `POST .../cancel` → `200`, `status=CANCELLED`, `refundStatus=COMPLETED`.
- В Coins hold переведён в `RELEASED`, созданы ровно одна `FREEZE`, одна `RELEASE` и одна `COMPLETE_CANCELLATION`; в Challenge release intent `CONFIRMED`.
- Кошелёк после отмены: `availableBalance=10`, `lockedBalance=10` (остался только hold другого challenge).
- Повторная отмена с новым `Idempotency-Key` вернула `200 COMPLETED` и не создала вторую release-операцию.
- Проверен отказ Coins: процесс Coins остановлен, затем `POST .../cancel` → `200`, `status=CANCELLED`, `refundStatus=PENDING`; release intent остался `PENDING`.
- После запуска Coins recovery worker перевёл возврат в `COMPLETED`; в Coins hold `RELEASED`, по одной операции `FREEZE`, `RELEASE`, `COMPLETE_CANCELLATION`, кошелёк вернулся к `availableBalance=10`, `lockedBalance=10`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок: ошибок нет.
- Ограничение: недоступность Coins имитирована остановкой локального процесса; время recovery зависит от интервала worker 5 секунд. Deploy не менялся.

### 2026-11-17 — T006.5, результат и settlement

- Обнаружен и закрыт пробел: маршрут `GET /api/v1/challenges/{challengeId}/result` был в контракте и Gateway, но отсутствовал в Challenge. Добавлены `ChallengeResultQueryService` и `ChallengeResultResponse`; endpoint возвращает `PENDING`, `FINAL` или `CANCELLED`, `viewerOutcome` (`WON`/`LOST`/`DRAW`/`null`) и `winnersCount`.
- `ChallengeReadRepository.findResult` читает результат, участие viewer и признак победителя без изменения данных.
- Добавлен HTTP-тест в `ChallengeResultFlowIntegrationTest`: `PENDING → FINAL`, `viewerOutcome` для победителя, проигравшего и постороннего.
- Через mTLS `FinalizeResult` зафиксирован `SINGLE_WINNER`; вызов выполнен клиентом с SAN `spiffe://fitness.local/result-source`.
- `challenge.finished` опубликован из outbox; Coins рассчитал банк 20, сохранил allocation 20 `PAID`, перевёл оба hold в `SETTLED`, stake set — в `SETTLED`, settlement — в `COMPLETED`.
- `GET /result` через Gateway: `status=FINAL`, `outcome=SINGLE_WINNER`, `viewerOutcome=WON`, `winnersCount=1`; `GET challenge`: `status=FINISHED`, `settlementStatus=COMPLETED`.
- Кошельки: победитель `availableBalance=20`, `lockedBalance=10`; проигравший `availableBalance=10`, `lockedBalance=0`.
- Повторная публикация того же `challenge.finished` в Kafka не изменила балансы: settlements 1, allocations 1, inbox 1.
- `sh ./mvnw -B -ntp -pl services/challenge-service -am verify`: `BUILD SUCCESS`, challenge 73/73, SpotBugs 0.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок: ошибок нет.
- Ограничение: источник результата в локальной проверке — тестовый mTLS-клиент, production-источник ещё не определён. Deploy не менялся.

### 2026-11-17 — T006.6, security и ошибки через Gateway

- `GET /api/v1/challenges/{id}` без токена через Gateway: `401`, код `invalid_access_token`, единый формат ошибки с `requestId`.
- Внутренний gRPC через Gateway недоступен: `grpcurl -plaintext host.docker.internal:8088 list` завершился ошибкой соединения; Gateway не проксирует gRPC.
- Недоступность БД Challenge: роль `fitness_challenge_app` переведена в `NOLOGIN`, соединения завершены. `GET challenge` через Gateway вернул `503 service_unavailable` без деталей БД и с `requestId`.
- После восстановления роли запрос вернул `200`, challenge сохранил `FINISHED` и `settlementStatus=COMPLETED`.
- Неизвестный ответ Coins: процесс Coins остановлен, затем `POST .../publish` вернул `202` с `operationId`, `status=PENDING` и `statusUrl`; операция сохранена как `PENDING`.
- После запуска Coins recovery worker завершил операцию после получения reserve по тому же operation ID; challenge перешёл в `OPEN`, операция — в `CONFIRMED`.
- `git diff --check`: успешно. Проверка относительных Markdown-ссылок: ошибок нет.
- Ограничение: `NOT_FOUND` от Coins не считается доказательством отсутствия команды, поэтому операция ожидает; для локального завершения reserve был отправлен повторно с тем же ID. Это соответствует текущей политике recovery, но не заменяет production-механизм разбора. Deploy не менялся.
