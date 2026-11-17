# План реализации Spec 020

> **Спецификация:** [spec.md](spec.md)
> **Статус:** implemented — MVP реализован и проверен 2026-11-17.
> **Ветка:** `feature/50-challenge-service-dev`
> **Задачи:** [tasks.md](tasks.md)
> **Стандарты:** [инженерные стандарты](../../docs/engineering-standards.md), [конституция](../constitution.md)

## Краткое решение

`challenge-service` получает собственные данные в PostgreSQL `fitness_challenge`, HTTP API через существующий Gateway и mTLS-gRPC-интеграцию с `coins-service`. Денежные операции выполняются как долговечные: сначала локально сохраняется операция, затем вызывается Coins, затем под блокировкой challenge подтверждается результат. HTTP-идемпотентность и server operation ID защищают от повторов; recovery worker продолжает операции после рестарта.

События публикуются через outbox. `challenge.finished` сообщает Coins окончательный результат; Coins получает неизменяемый список победителей через `ListResultWinners`, выполняет расчёт для двух holds одной локальной транзакцией и публикует `coins.settlement_completed`. Challenge применяет это событие через inbox и сохраняет проекцию расчёта.

MVP обеспечивает исправленные относительно исходного черновика границы:

- ставки описаны protobuf-контрактом, а не OpenAPI;
- используется `BeginStakeSet` / `PutStakeBatch` / `FinalizeStakeSet` вместо `SealStakeSet`;
- добавляется `GetStakeOperation` для разрешения потерянных ответов;
- отмена защищена `FreezeStakeSet` / `CompleteStakeCancellation`;
- внутренний результат фиксируется gRPC `FinalizeResult` по mTLS, а не HTTP через Gateway;
- события называются `challenge.finished` и `coins.settlement_completed`;
- Kafka, recovery worker, хранение идемпотентности и mTLS входят в объём.

## Затрагиваемые границы

| Область | Изменение | Владелец данных / контракта |
| --- | --- | --- |
| `gateway-service` | Upstream `CHALLENGE`, конфигурация, маршруты Challenge с `AUTHENTICATED`, проверка forwarding | Gateway (маршруты и политика доступа) |
| `contracts` | `challenge-v1.yaml`, `coins-stake-v1.proto`, `challenge-results-v1.proto`, `$ref` в `gateway-v1.yaml` | Challenge и Coins |
| `challenge-service` | БД, доменные переходы, HTTP API, gRPC-клиент Coins, gRPC-сервер результата, outbox/inbox, recovery | Challenge |
| `coins-service` | Holds, ledger, stake set, freeze/complete, settlement, события | Coins |
| `deployment` | Только локальный Kafka Compose и шаблоны окружения; серверная выкладка Challenge не входит | Deployment (документируется отдельно) |
| `docs` | Подключение базы Challenge, локальный запуск, контракты и ограничения | Документация проекта |

## Данные и миграции

### Challenge: `fitness_challenge`

MVP-подмножество [модели 38](../../system-design/services/challenge/38-challenge-data-model.md):

- `challenges`;
- `challenge_participants`;
- `challenge_operations` с `kind` `PUBLISH`/`JOIN`;
- `challenge_stake_sets`;
- `challenge_stake_set_items`;
- `challenge_release_intents`;
- `challenge_results`;
- `challenge_result_winners`;
- `challenge_settlement_status`;
- `challenge_outbox`;
- `challenge_inbox`;
- `challenge_idempotency_records`.

Не создаются: дни, задания, состояния заданий и дней, право и апелляции, доступ, review admissions, аудитории, event journal, pending events, lifecycle jobs.

Ключевые ограничения:

- `challenges.version` защищает конкурентное изменение через HTTP;
- `challenge_participants` — `UNIQUE (challenge_id, user_id)` и `UNIQUE (challenge_id, join_sequence)`;
- `challenge_operations` — частичный уникальный индекс незавершённых операций на `(challenge_id, user_id)`;
- `challenge_release_intents.hold_id` — `UNIQUE`;
- `challenge_results.challenge_id` — `UNIQUE`;
- `challenge_result_winners` — PK `(result_id, user_id)`;
- `challenge_settlement_status.source_event_id` — `UNIQUE`;
- `challenge_idempotency_records` — `UNIQUE (user_id, method, path, key)`;
- денежные поля — `BIGINT` с проверками; digest — `BYTEA` длиной 32;
- для не-`DRAFT` CHECK требует обязательные поля публикации;
- `created_at`/`updated_at` — `timestamptz NOT NULL`.

Транзакционные границы: локальное изменение и outbox — один commit; внешние gRPC-вызовы — только после commit; подтверждение участия и увеличение `next_join_sequence` — под блокировкой строки challenge.

### Coins: `fitness_coins`

Используются существующая `coin_wallets` и таблицы [модели 36](../../system-design/services/coins/36-coins-data-model.md):

- `coin_holds`;
- `coin_ledger_entries`;
- `coin_stake_sets`;
- `coin_stake_operations`;
- `coin_sealed_stakes`;
- `coin_settlements`;
- `coin_settlement_winners`;
- `coin_settlement_allocations`;
- `coin_settlement_hold_moves`;
- `coin_inbox`;
- `coin_outbox`.

Не создаются: escrow, journal, pending events, recovery actions, challenge projection, daily reward.

Для MVP расчёт банка из двух holds выполняется одной транзакцией: каждый hold переводится `ACTIVE → SETTLED`, с locked-баланса списывается сумма, победителю зачисляется доступный баланс; сумма выплат равна сумме holds. Остатка нет: при `SINGLE_WINNER` банк двойной ставки, при `DRAW` каждый получает свою ставку.

Миграции выполняются только Liquibase и только после отдельного разрешения пользователя.

## Контракты и взаимодействия

| Направление | Тип | Изменение | Версионирование / совместимость |
| --- | --- | --- | --- |
| Клиент → Gateway → Challenge | REST | `POST drafts`, `PATCH draft`, `publish`, `joins`, `cancel`, `GET challenge`, `GET challenge-operations/{id}`, `GET result` | Новый owner-контракт `challenge-v1.yaml`, v1 |
| Gateway | REST | `$ref` на путь Challenge, `protected-paths`, публичный `503` | `gateway-v1.yaml` без дублирования схем |
| Challenge → Coins | gRPC | Reserve/Release/GetStakeOperation, Begin/Put/Finalize, Freeze/CompleteCancellation | `contracts/proto/coins-stake-v1.proto`, mTLS |
| Источник результата → Challenge | gRPC | `FinalizeResult` | `contracts/proto/challenge-results-v1.proto`, mTLS |
| Coins → Challenge | gRPC | `ListResultWinners(resultId, cursor, limit)` | Тот же protobuf, mTLS |
| Challenge → Kafka | Событие | `challenge.finished` v1 | Outbox, топик `fitness.challenge.finished.v1` |
| Coins → Kafka | Событие | `coins.settlement_completed` v1 | Outbox, топик `fitness.coins.settlement_completed.v1` |

Формат события: snake_case-оболочка, camelCase `data`. Для JSON-полей HTTP API используется camelCase согласно [стандартам](../../docs/engineering-standards.md). Внутренний gRPC не открывается в интернет.

`challenge-v1.yaml`:

- `Idempotency-Key` обязателен на изменяющих методах;
- успешные ответы `200`/`201`/`202`; `202` содержит `operationId`, `status`, `statusUrl`;
- ошибки — общий формат с `requestId`;
- `GET challenge` содержит `refundStatus` и `settlementStatus`;
- `GET result` содержит `PENDING`/`FINAL`/`CANCELLED` и `viewerOutcome`;
- закрытые схемы: лимиты из `spec.md`, неизвестные поля запрещены;
- публичный `503 challenge_unavailable` описан в Gateway-контракте, не дублируется в Challenge.

`coins-stake-v1.proto` переносится в `contracts/proto` из [черновика](../../system-design/contracts/coins-stake-v1.proto) и дополняется `GetStakeOperation`, `FreezeStakeSet`, `CompleteStakeCancellation`. `FinalizeResult` добавляется в [challenge-results-v1.proto](../../system-design/contracts/challenge-results-v1.proto). Версия `fitness-grpc-contracts` повышается; зависимости обновляются у `identity-service`, `accounts-service`, `coins-service`, `gateway-service` и `challenge-service`, чтобы сборка не смешивала версии.

## Надёжность и безопасность

- Gateway — единственный валидатор пользовательского JWT и сессии; Challenge разбирает forwarded Bearer JWT по образцу Accounts/Coins, кладёт `sub` в `SecurityContext` и очищает контекст после запроса.
- Внутренние gRPC-вызовы защищены mTLS; Challenge разрешает stake-операции только `coins-service`, Coins — только `challenge-service`; источник результата — отдельный доверенный SAN.
- `FinalizeResult` не публикуется через Gateway; прямой вызов без mTLS отклоняется.
- Ошибки security проходят через `SecurityExceptionHandler` и общий `ApiExceptionHandler`; неперехваченное исключение даёт `500 internal_error` без подробностей.
- Durable-операции: запись до внешнего вызова, повтор с тем же ID, recovery worker с `FOR UPDATE SKIP LOCKED` и lease.
- Pub/join: неизвестный результат Coins — `202`, не ошибка; окончательный `409 challenge_full` — только после подтверждённого release.
- Outbox publisher: claim в короткой транзакции, публикация после commit, lease и повтор; `published_at` не считается доказательством доставки.
- Inbox: уникальность `(consumer, event_id)`, проверка digest, повторное применение не меняет состояние; другой digest — карантин.
- Отмена: freeze запрещает новые резервы, complete проверяет отсутствие активных holds; release intent один на hold.
- Логи не содержат токенов, `Idempotency-Key`, тел запросов, сумм чужих кошельков и SQL.
- gRPC deadline Challenge → Coins меньше 7 секунд Gateway; при неизвестном исходе операция остаётся `PENDING`.
- Наблюдаемость: health/readiness, метрики операций и outbox backlog; персональные данные не попадают в метки.

## Порядок работ

1. Спецификация и контракты.
2. Gateway: upstream, конфигурация, маршруты, тесты.
3. Challenge: каркас зависимостей, миграции, security, draft/patch/read.
4. Coins: Reserve/Release и Challenge publish/join/cancel как один вертикальный срез.
5. Coins: seal и Challenge WHEN_FULL.
6. Результат, Kafka, settlement, inbox и проекция.
7. Recovery worker, тесты гонок и отказов.
8. Сквозная проверка через Gateway и документация.

## Проверка

- Unit-тесты доменных переходов, идемпотентности и маппинга ошибок.
- Repository-тесты PostgreSQL через Testcontainers: блокировки, version, уникальность последнего места, release intent, outbox claim.
- Web/security-тесты: `200/201/202/400/401/404/409/422/503`, формат ошибок, `requestId`, чужой draft, отсутствие JWT.
- Concurrency/failure-тесты: два join за последнее место, cancel против join, потерянный ответ Coins до и после commit, рестарт recovery worker, повтор seal.
- gRPC-тесты: mTLS, авторизация service identity, идемпотентность, конфликт operation ID, deadline.
- Kafka-тесты: публикация outbox, повтор, digest, inbox, карантин.
- Сквозной сценарий через Gateway: publish → join → ACTIVE → result → settlement; cancel и возврат.
- `./mvnw -B -ntp -pl <module> -am verify`, SpotBugs, `git diff --check`, проверка Markdown-ссылок.
- Результаты и ограничения — в [verification.md](verification.md).

## Риски и решения

| Риск или открытый вопрос | Решение / кто принимает решение |
| --- | --- |
| PostgreSQL и Kafka недоступны в окружении | Тесты используют Testcontainers; ограничение фиксируется в verification, а не выдаётся за успех. |
| Ограничения title/description/stake/duration не утверждены | Значения из `spec.md` подтверждает пользователь до `approved`. |
| Production-источник результата не определён | MVP использует внутренний gRPC с mTLS; публичного назначения победителя нет. |
| Ошибки конфигурации mTLS и сертификатов | `initialize-local-env.ps1` дополняется безопасной генерацией недостающих материалов без замены CA. |
| Потерянные ответы Coins | Durable operation ID, `GetStakeOperation`, recovery worker и отсутствие автоматического release. |
| Конкурентные join и cancel | Блокировка строки challenge, частичный уникальный индекс операций, release intent с `UNIQUE(hold_id)`. |
| Долгая недоступность БД относительно deadline Gateway | Отдельная проверка; при неизвестном итоге клиент получает `202`, а не ложный отказ. |
| Локальная Kafka отсутствует | Добавляется отдельный локальный Compose KRaft; production Compose и release scripts не меняются. |
| Смешение версий gRPC-контрактов | Одновременное обновление всех потребителей `fitness-grpc-contracts`. |
| Расхождение модели 38 и MVP | В спеке явно перечислены создаваемые таблицы; остальные не входят. |
