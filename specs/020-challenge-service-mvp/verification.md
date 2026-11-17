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
