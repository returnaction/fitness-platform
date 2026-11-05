# Снимки восстановления и сверка Coins: проект v1

Будущая Java / Spring-система; это проект схем и API, не работающий сервис,
не миграция и не гарантированная процедура восстановления. Базовый поток:
[контракт восстановления](54-event-recovery-contract.md). Схема денежных
таблиц определена только в [модели Coins](../../services/coins/36-coins-data-model.md).

## Границы

Recovery Coordinator управляет сверкой, но не редактирует чужие данные. Каждый
владелец создаёт свой снимок в своей БД в режиме `RECOVERY`; запросы доступны
только авторизованному координатору по gRPC с `run_id`, `epoch`, `request_id`,
service identity и ограниченными правами. Изоляция и проверка актуального epoch
на уровне БД должны не допускать неучтённого writer. Необработанные WAL и
отсутствующие ledger-записи нельзя заменять расчётом по предположениям.

## Таблицы снимков — единая схема для реализации каждым владельцем

Ниже одно каноническое определение структуры, разворачиваемой **отдельно** в
БД Challenge, Moderation, Video Proof и Coins. Таблицы не общие между сервисами.
Схема требует DDL и тестов; имена физически одинаковые в разных базах.

### `recovery_export_snapshots`

| Поле | Тип и ограничения | Назначение |
| --- | --- | --- |
| `id` | UUID PK | Идентификатор снимка. |
| `run_id`, `epoch`, `operation_id` | UUID NOT NULL | Владелец запуска и идемпотентной операции; UNIQUE `(run_id, operation_id)`. |
| `kind` | VARCHAR(32) NOT NULL | `EVENT_JOURNAL`, `COINS_CHALLENGE`, `CHALLENGE_RESULT`. |
| `scope` | JSONB NOT NULL | Версионированная область, включая challenge ID где требуется. |
| `request_digest` | BYTEA NOT NULL, 32 байта | Защита от другого запроса с тем же operation ID. |
| `state` | VARCHAR(16) NOT NULL | `BUILDING`, `SEALED`, `BLOCKED`, `RELEASED`. |
| `journal_upper_seq` | BIGINT NULL CHECK >= 0 | Граница журнала при остановленных writers; NULL для иных снимков. |
| `item_count` | BIGINT NULL CHECK >= 0 | Число строк, обязательно после `SEALED`. |
| `manifest_sha256` | BYTEA NULL, 32 байта | Хеш полного упорядоченного манифеста, обязателен после `SEALED`. |
| `created_at`, `sealed_at` | timestamptz NOT NULL, timestamptz NULL | Время создания и запечатывания; sealed_at обязателен после SEALED. |

`SEALED` не допускает изменения состава и содержания. `RELEASED` — явное
разрешение на последующую безопасную очистку, не автоматическое удаление
непроверенного снимка. Освобождать снимок текущего незавершённого run нельзя.
При одинаковом ключе и иных параметрах — конфликт, а не замена области.

### `recovery_export_items`

| Поле | Тип и ограничения | Назначение |
| --- | --- | --- |
| `snapshot_id` | UUID NOT NULL FK → `recovery_export_snapshots.id` | Снимок. |
| `item_index` | BIGINT NOT NULL CHECK >= 0 | Порядок; PK `(snapshot_id,item_index)`. |
| `item_type` | VARCHAR(32) NOT NULL | Тип записи из каталога snapshot kind. |
| `business_key` | TEXT NOT NULL | Стабильный ключ записи; UNIQUE `(snapshot_id,item_type,business_key)`. |
| `canonical_bytes` | BYTEA NOT NULL | Каноническая сериализация содержимого на момент снимка. |
| `item_sha256` | BYTEA NOT NULL, 32 байта | SHA-256 именно сохранённых bytes. |

Снимок материализуется в БД владельца и перечитывается после перезапуска;
это не открытая транзакция PostgreSQL на время работы сети. Состав создаётся
только после проверки epoch и остановки обычных writers. Длинная материализация
может занимать несколько транзакций лишь при сохранении подтверждённой блокировки
writers до `SEALED`; иначе снимок запрещён. `BUILDING` не выдаётся получателю.
Фиксация границы, count, хеша и `SEALED` выполняется после проверки полного
состава. Ошибка — `BLOCKED`; частичный снимок не используется.

## Манифест и кодирование v1

Проектный wire payload — UTF-8 JSON без неизвестных обязательных полей;
канонизация JSON — RFC 8785, значения BIGINT представляются десятичными
строками, без плавающих денежных значений. UUID — lowercase с дефисами, время
UTC; схема каждого item содержит `schema_version: 1`. В v1 строки нормализуются
**только согласно выбранной схеме** до построения snapshot; доменные строки
не преобразуются молча после подписания. Каждое `canonical_bytes` сохраняется,
`item_sha256 = SHA-256(canonical_bytes)`; проверка делается на обеих сторонах.

Порядок индексов: для журнала — `journal_seq` по возрастанию после остановки
всех обычных и recovery writers соответствующего потока; для Coins/Challenge
— сортировка по `item_type` и `business_key` в сравнении байтов UTF-8, без
locale; индекс назначается подряд с 0. Содержимое манифеста:

```text
SHA-256(UTF8("fitness.recovery.snapshot.v1\n") ||
        U64_BE(item_count) ||
        для каждой строки по item_index: U64_BE(item_index) || item_sha256)
```

U64_BE — ровно 8 беззнаковых байтов в big-endian; диапазон индексов и количества
ограничен положительным диапазоном PostgreSQL BIGINT. Пустой снимок хеширует
заголовок и восемь нулевых байтов. Разные item contents с одинаковым count не
считаются равными. Перед реализацией требуются межъязыковые тестовые векторы,
проверка RFC 8785 и безопасных диапазонов JSON, а также сопоставление с уже
описанным `payload_digest` событий: нельзя молча заменить алгоритм существующих
контрактов или пересчитать старые значения без версии/миграции.

## gRPC: работа со снимком

В каждом запросе `run_id`, `epoch`, `request_id`; изменяющие запросы содержат
`operation_id` UUID. gRPC вызовы не публичные и используют service identity.
`DEADLINE_EXCEEDED` / `UNAVAILABLE` — исход неизвестен, опрос/повтор с тем же
ID. `PERMISSION_DENIED` — нет полномочий; `FAILED_PRECONDITION` — режим или
epoch неверен; `INVALID_ARGUMENT` — некорректный DTO; `ALREADY_EXISTS` —
конфликт повторного operation ID.

| Метод | Запрос | Ответ при успехе |
| --- | --- | --- |
| `OpenRecoverySnapshot` | `operation_id`, `kind`, `scope` | `snapshot_id`, `state` (`BUILDING` или `SEALED`); до готовности опрашивать статус. |
| `GetRecoverySnapshot` | `snapshot_id` | `state`, после SEALED — `item_count`, `manifest_sha256`, `journal_upper_seq` при наличии. |
| `ListRecoverySnapshotItems` | `snapshot_id`, `after_index` (NULL для первой страницы), `limit` 1–500 | `items[{item_index,item_type,business_key,canonical_bytes,item_sha256}]`, `next_after_index` NULL на последней странице. |
| `ReleaseRecoverySnapshot` | `operation_id`, `snapshot_id` | `state: RELEASED` только если завершён run и сохранён отчёт; иначе `FAILED_PRECONDITION`. |

Страницы упорядочены по item_index, повтор страницы идентичен. Получатель
проверяет индексы без пропусков, каждый hash и итоговый manifest; недостаточно
только совпадения количества. Новый epoch или изменение области не позволяет
продолжить старую выдачу без подтверждённого возобновления того же run.
Максимальный размер страницы в байтах, пределы JSON и серверное шифрование
канала задаются до реализации; нельзя отправить неограниченный payload.

## Снимок `EVENT_JOURNAL`

Каждая запись имеет `item_type: EVENT` и стабильный `business_key: event_id`.
Содержимое v1: `event_id`, `event_type`, `schema_version`, `aggregate_id`,
`journal_seq` строкой BIGINT, `payload` и `payload_digest` с явным алгоритмом
исходной версии события. Передача исходного event_id не создаёт новый факт.
Журнал каждого владельца определён в его модели. Область включает поток и
получателя; правила маршрутизации конкретных типов и их версии должны быть
зафиксированы до формирования snapshot. Отсутствующий журнал или покрытие
неизвестных периодов — BLOCKED, а не пустой успешный снимок.

## Снимок и API сверки конкретного challenge

Challenge создаёт `CHALLENGE_RESULT`, Coins — `COINS_CHALLENGE`, scope:
`{"schema_version":1,"challenge_id":"<uuid>"}`. Для полной сверки оба
снимка должны быть `SEALED` в одном recovery run после надёжной остановки
соответствующих writers. Они не являются магическим общим snapshot разных БД:
временные расхождения и неприменённые события выявляются по ID и версиям.

### Формат записей Challenge

| `item_type` | `business_key` | Обязательные данные v1 |
| --- | --- | --- |
| `CHALLENGE_STAKE_SET` | challenge UUID | `challenge_id`, `set_version` строкой BIGINT, `state`, `digest_hex` либо NULL, `item_count` строкой BIGINT либо NULL. |
| `CHALLENGE_STAKE_ITEM` | hold UUID | `challenge_id`, `set_version`, `hold_id`, `user_id`, `amount` строкой положительного BIGINT. |
| `CHALLENGE_RESULT` | result UUID | `challenge_id`, `result_id`, `set_version`, `outcome`, `rule_version`, `winner_count`, `winners_digest_hex`. |
| `CHALLENGE_WINNER` | user UUID | `result_id`, `user_id`. |

Отсутствие окончательного результата допустимо, если состояние challenge
ещё не предполагает результат; сверка не подменяет это пустым выигрышем.
Определения исходных таблиц — в [Challenge](../../services/challenge/38-challenge-data-model.md).

### Формат записей Coins

| `item_type` | `business_key` | Обязательные данные v1 |
| --- | --- | --- |
| `COIN_STAKE_SET` | challenge UUID | `challenge_id`, `state`, `set_version` / `digest_hex` или NULL, `cancellation_id` или NULL. |
| `COIN_HOLD` | hold UUID | `hold_id`, `user_id`, `challenge_id`, `join_operation_id`, `amount`, `status`. |
| `COIN_STAKE_OPERATION` | `kind:operation_uuid` | `kind`, `operation_id`, `challenge_id`, `request_hash_hex`, `result` без секретов. |
| `COIN_SETTLEMENT` | settlement UUID | `settlement_id`, `challenge_id`, `result_id`, `set_version`, `rule_version`, `phase`, `pool`, `winner_count`, `winners_digest_hex`, `seed_digest_hex` или NULL. Сам seed не передаётся. |
| `COIN_ALLOCATION` | `settlement_uuid:user_uuid` | `settlement_id`, `user_id`, `amount`, `remainder_bonus`, `status`. |
| `COIN_HOLD_MOVE` | `settlement_uuid:hold_uuid` | `settlement_id`, `hold_id`, `amount`. |
| `COIN_ESCROW` | settlement UUID | `settlement_id`, `balance`. |
| `COIN_ESCROW_ENTRY` | entry UUID | `settlement_id`, `operation_id`, `wallet_ledger_entry_id`, `delta`. |
| `COIN_WALLET` | user UUID | `user_id`, `available_balance`, `locked_balance`. |
| `COIN_LEDGER_ENTRY` | entry UUID | `entry_id`, `user_id`, `operation_id`, `entry_index`, `type`, `available_delta`, `locked_delta`, `challenge_id` или NULL, `reverses_entry_id` или NULL. |

Денежные числа и счётчики BIGINT — строки; все записи содержат
`schema_version:1`. Снимок включает **все** ledger-записи затронутых
кошельков, включая другие challenge, либо подтверждённый начальный остаток и
полную историю после него. В текущей модели начальная точка не определена,
поэтому v1 использует полную доступную историю кошельков и BLOCKED при её
недостатке. Срез только по `challenge_id` не доказывает корректность баланса.
Предел объёма, экспорт и доступ к чужим кошелькам ограничены служебным scope;
данные не показываются пользователям или модераторам.

`CompareCoinsChallenge` — координатор сравнивает оба запечатанных снимка,
не меняя их: `{run_id,epoch,challenge_snapshot_id,coins_snapshot_id}` →
`{status: MATCH|WAITING|CONFLICT, report_id, difference_count}`. `MATCH`:
идентичные устойчивые ID/версии и digests закрытого набора и результата,
каждый hold/operation/ledger связан с правильной фазой, сумма allocations
равна банку после завершения подготовки, а проведённые движения и escrow
соответствуют фазе. Частичный settlement **не обязан** иметь итоговый нулевой
escrow; он проверяется по правилам своей фазы. Несовпадение из-за ещё не
применённого достоверного события — WAITING, противоречие или недостаток
данных — CONFLICT и BLOCKED. `MATCH` не доказывает факт, отсутствующий в обеих
базах; нужны независимые журналы и проверенный backup.

Для проверки Coins отдельно сверяются неизменность и уникальность бизнес-ID,
`hold_moves` с принадлежащими набору holds и их суммой, уникальность выплат
получателям и соответствие каждого денежного движения связанной wallet-ledger
записи. Нулевая allocation может быть `PAID` без проводки и не меняет escrow.
Сумма `coin_settlement_escrow_entries.delta` с нулевого начального остатка
должна совпасть с сохранённым `escrow.balance`; сумма проведённых входов за
вычетом проведённых выплат — с тем же остатком. `COMPLETED` требует все
allocation `PAID`, полного соответствия банка и нулевого escrow. Для частичной
фазы сравнивают только подтверждённые действия, не предполагая полный сбор или
выплату. Сумма записей ledger кошелька проверяется по всей его истории, а не
только по восстанавливаемому challenge. Любое расхождение или отсутствие
доказательств — `CONFLICT`, без автоматического изменения остатков.

Корректирующая проводка не является способом восстановить потерянный commit на
основании догадки. Если исходный факт подтверждён достоверными независимыми
данными, его применяют через обычную идемпотентную операцию владельца с прежним
бизнес-ID; противоречие остаётся заблокированным для отдельного аудируемого
разбора. Бизнес-политика исправления уже окончательного результата и потраченных
выплат отложена; `resume` не позволяет её обойти.

`GetCoinsReconciliationReport(report_id)` возвращает постранично
`{status, differences:[{code,business_id,expected,actual}], next_cursor}` для
оператора с `recovery.read`; суммы и user IDs ограничены областью разрешения,
не попадают в обычные логи. Отчёт нельзя использовать как команду изменить
баланс. Нужна долговечная таблица отчёта/строк в БД координатора с ID запуска,
двумя snapshot IDs, digest и ограничениями идемпотентности; конкретное DDL
ниже. Сравнение запускается с `operation_id`, повтор с тем же payload выдаёт
тот же report, другой payload — конфликт. Недоступность одной из баз или
изменение epoch — UNAVAILABLE/FAILED_PRECONDITION, не MATCH.

## Таблицы отчёта координатора

`recovery_comparison_reports`: `id UUID PRIMARY KEY`, `run_id UUID NOT NULL FK →
recovery_runs.id`, `operation_id UUID NOT NULL`, `request_digest BYTEA NOT NULL`
длиной 32, `challenge_snapshot_id UUID NOT NULL`, `coins_snapshot_id UUID NOT
NULL`, `state VARCHAR(16) NOT NULL` (`BUILDING`, `MATCH`, `WAITING`, `CONFLICT`),
`difference_count BIGINT NOT NULL DEFAULT 0 CHECK >= 0`, `created_at`,
`updated_at timestamptz NOT NULL`, UNIQUE `(run_id, operation_id)`.
Внешние snapshot IDs не имеют межбазовых FK.

`recovery_comparison_differences`: `report_id UUID NOT NULL FK →
recovery_comparison_reports.id`, `item_index BIGINT NOT NULL CHECK >= 0`,
`code VARCHAR(64) NOT NULL`, `business_id TEXT NOT NULL`, `expected JSONB NULL`,
`actual JSONB NULL`, PK `(report_id,item_index)`. Отчёт фиксируется целиком
или возобновляется контролируемой job; `MATCH` допустим только после сверки
всех страниц и манифестов. Эти данные чувствительны: права и retention
координатора должны быть определены до внедрения; события в логи не писать.

## Проверки до разработки

Проверить контрольные векторы хеша (включая пустой снимок и русский текст),
переполнение и значения > 2^53, два concurrent open с одним operation ID,
падение при BUILDING, stale epoch, writers во время snapshot, повтор страницы,
подмену байтов, неравные снимки при равном count, пропуск страницы, нагрузку на
полный ledger, снимки разных фаз расчёта, частичные выплаты, потерянный ответ,
отсутствие истории и невозможность восстановить RPO 0. Интеграционные тесты
пока не запускались.
