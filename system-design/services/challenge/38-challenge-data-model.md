# Challenge: проект модели данных

Это спецификация будущего Java / Spring-сервиса, не схема существующей БД и не
миграция. Challenge владеет соревнованием, расписанием, участниками и их
состоянием. Coins владеет резервами и денежным журналом; Video Proof — видео;
Moderation — решениями. Между базами нет внешних ключей.

Согласованные состояния и правила: [статусы](../../overview/22-architecture-status.md),
[вступление](20-join-service-contract-draft.md), [отмена](21-challenge-cancellation-draft.md),
[четыре решения](../moderation/25-moderation-decisions.md). Таблицы операций ставок и результата определены ниже; контракт расчётов ссылается на них.

## Общие договорённости проекта

UUID идентифицирует записи; время хранится как `timestamptz` в UTC;
`created_at`, `updated_at` обязательны там, где указаны. Приведённые таблицы
— предлагаемый проект, а не утверждение о существующих миграциях. Денежные
значения — целые виртуальные монеты `BIGINT`, ставка строго положительна.
Состояния фиксируются в транзакциях Challenge; события публикуются через его
outbox. Внешние вызовы не удерживают открытую транзакцию базы.

## Основные таблицы

### `challenges`

| Поле | Тип | Правило |
| --- | --- | --- |
| `id` | UUID PK | Идентификатор challenge. |
| `creator_user_id` | UUID NOT NULL | Идентификатор из Identity, без межсервисного FK. |
| `title`, `description` | TEXT NULL | Условия для просмотра; title обязателен при публикации, точные лимиты требуют решения API. |
| `mode` | VARCHAR NULL | Для опубликованного обязательный поддерживаемый режим; в v1 `HEAD_TO_HEAD` или `GROUP`. |
| `status` | VARCHAR NOT NULL | `DRAFT`, `OPEN`, `ACTIVE`, `AWAITING_RESULTS`, `FINISHED`, `CANCELLED`. |
| `stake_amount` | BIGINT NULL | Для опубликованного обязательно `> 0`; недоступные средства нельзя использовать. |
| `min_participants`, `max_participants` | INTEGER NULL | Для опубликованного min обязателен; max NULL допустим для группы без верхнего предела, иначе не меньше min. |
| `visibility` | VARCHAR NOT NULL | Значения и сочетание доступа по ссылке и паролю требуют отдельного контракта. |
| `start_mode` | VARCHAR NULL | `WHEN_FULL` или `AT_SCHEDULE`; при публикации обязателен. |
| `extension_max_count` | INTEGER NULL | Для опубликованного обязателен и >= 0. |
| `extension_interval_seconds` | BIGINT NULL | Положительный при разрешённых переносах; иначе NULL. |
| `extension_count` | INTEGER NOT NULL DEFAULT 0 CHECK >= 0 | Число уже выполненных переносов; не больше max при наличии. |
| `scheduled_start_at`, `started_at` | timestamptz NULL | Запланированное и фактическое время; правила старта описываются отдельно. |
| `duration_days` | INTEGER NULL | Для опубликованного обязательно от 1 до 30. |
| `next_join_sequence` | BIGINT NOT NULL DEFAULT 1 CHECK > 0 | Следующий порядковый номер подтверждённого участника. |
| `version` | BIGINT NOT NULL | Версия для защиты конкурентного изменения. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время записи. |

Индексы поиска и ограничения публикации зависят от контракта фильтрации.
Условия опубликованного challenge неизменяемы; перенос даты по заранее
согласованному правилу — отдельный переход, а не произвольное редактирование.
Черновик может быть неполным; CHECK в миграции должен требовать перечисленные
поля, когда статус не DRAFT, и допустимые комбинации по
[контракту расписания](60-challenge-schedule-and-access.md). Дефолт visibility
для нового draft — PUBLIC. Для `AT_SCHEDULE` обязателен scheduled_start_at;
для `WHEN_FULL` он NULL до фактического старта и max обязателен. Временные
условия «будущее» проверяются при публикации, а не CHECK с текущими часами.

### `challenge_days`

| Поле | Тип | Правило |
| --- | --- | --- |
| `id` | UUID PK | Идентификатор дня. |
| `challenge_id` | UUID NOT NULL FK → `challenges.id` | Принадлежность challenge. |
| `day_number` | INTEGER NOT NULL | `> 0`, UNIQUE `(challenge_id, day_number)`. |
| `opens_at`, `deadline_at` | timestamptz NULL | Фактический интервал дня; `deadline_at > opens_at`, если оба заданы. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время записи. |

Время дня фиксируется сервером; до назначения расписания может отсутствовать.
Определение длительности дня и вычисление времени при переносах нужно
зафиксировать в контракте расписания до реализации.

### `challenge_tasks`

| Поле | Тип | Правило |
| --- | --- | --- |
| `id` | UUID PK | Одно задание и максимум одна принятая видеозапись участника. |
| `challenge_day_id` | UUID NOT NULL FK → `challenge_days.id` | День задания. |
| `position` | INTEGER NOT NULL | `> 0`, UNIQUE `(challenge_day_id, position)`. |
| `exercise_id`, `variation_id` | SMALLINT NULL | Внешние ID Catalog в диапазоне `1`–`32767`, не FK между сервисами; обязательны для опубликованного задания. |
| `target_value` | BIGINT NULL | Целое положительное значение; обязательно для опубликованного задания и сверяется с диапазоном Catalog. |
| `exercise_snapshot` | JSONB NULL | Проверенные сервером название, вариация, описание и инструкция; обязателен после публикации; формат — [снимок задания](../exercise-catalog/49-task-metric-contract.md). |
| `target_snapshot` | JSONB NULL | Метрика и целевое значение; обязателен после публикации. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время записи. |

Снимок берётся из доверенного каталога и условий Challenge, а не из тела
загрузки видео клиента. Ограничение числа заданий и структуру JSON нужно
утвердить и валидировать до публикации; нельзя считать произвольный JSON
готовым контрактом. Несколько подходов представлены отдельными заданиями.

### `challenge_participants`

| Поле | Тип | Правило |
| --- | --- | --- |
| `id` | UUID PK | Идентификатор участия. |
| `challenge_id` | UUID NOT NULL FK → `challenges.id` | Соревнование. |
| `user_id` | UUID NOT NULL | Identity ID; UNIQUE `(challenge_id, user_id)`. |
| `status` | VARCHAR NOT NULL | `JOINED`, `ACTIVE`, `AWAITING_DECISION`, `ELIMINATED`, `WINNER`, `COMPLETED`. |
| `joined_at` | timestamptz NOT NULL | Серверное время подтверждённого участия. |
| `join_sequence` | BIGINT NOT NULL CHECK > 0 | Номер вступления внутри challenge; UNIQUE `(challenge_id,join_sequence)`. |
| `eliminated_day_id` | UUID NULL FK → `challenge_days.id` | День выбытия; должен относиться к тому же challenge. |
| `version` | BIGINT NOT NULL | Защита конкурентных изменений. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время записи. |

Создатель становится участником после успешной публикации и резервирования
ставки. Количество мест проверяется под блокировкой состояния challenge при
подтверждении вступления; одна уникальность пары не защищает последнее место.
Выход и удаление другого участника создателем не предусмотрены.

Для `join_sequence` используется определённый выше `next_join_sequence`.
Подтверждение участия под блокировкой challenge берёт
и увеличивает счётчик в той же транзакции; номера не переиспользуются. Это
позволяет фиксировать состав рассылки без зависимости от UUID или часов.

### `participant_task_states`

| Поле | Тип | Правило |
| --- | --- | --- |
| `participant_id` | UUID NOT NULL FK → `challenge_participants.id` | Участие. |
| `task_id` | UUID NOT NULL FK → `challenge_tasks.id` | Задание того же challenge. |
| `status` | VARCHAR NOT NULL | `NOT_STARTED`, `SUBMITTING`, `UNDER_REVIEW`, `AWAITING_APPEAL`, `COMPLETED`, `FAILED`. |
| `submission_id` | UUID NULL | Идентификатор Video Proof; локального FK нет. |
| `version` | BIGINT NOT NULL | Защита конкурентных переходов. |
| `updated_at` | timestamptz NOT NULL | Время изменения. |

PK `(participant_id, task_id)`; принадлежность одному challenge должна быть
обеспечена локальными ограничениями или проверкой под блокировкой. Состояние
не подменяет решение модератора и состояние видео. Механизм защиты от принятия
двух записей одновременно должен быть определён совместно с Video Proof.

### `participant_day_states`

| Поле | Тип | Правило |
| --- | --- | --- |
| `participant_id` | UUID NOT NULL FK → `challenge_participants.id` | Участие. |
| `challenge_day_id` | UUID NOT NULL FK → `challenge_days.id` | День того же challenge. |
| `status` | VARCHAR NOT NULL | `IN_PROGRESS`, `AWAITING_RESULTS`, `COMPLETED`, `FAILED`. |
| `version` | BIGINT NOT NULL | Защита конкурентного пересчёта. |
| `updated_at` | timestamptz NOT NULL | Время изменения. |

PK `(participant_id, challenge_day_id)`. День выполнен, когда все обязательные
задания выполнены; одно окончательно невыполненное задание означает `FAILED`.
Конкретную транзакцию пересчёта, порядок блокировок и обработку событий после
выбытия нужно описать до реализации.

## Право и поданная апелляция

`challenge_appeal_rights`: `id UUID PRIMARY KEY`, `submission_id UUID NOT NULL
UNIQUE`, `original_decision_id UUID NOT NULL UNIQUE`, `participant_id UUID NOT
NULL FK → challenge_participants.id`, `task_id UUID NOT NULL FK →
challenge_tasks.id`, `state VARCHAR(16) NOT NULL` (`AVAILABLE`, `SUBMITTED`,
`EXPIRED`, `CANCELLED`), `deadline_at timestamptz NOT NULL`, `version BIGINT
NOT NULL CHECK > 0`, `created_at`, `updated_at timestamptz NOT NULL`.
ID исходного решения и отправки внешние, не FK. Принадлежность участника и
задания одному challenge проверяется локально под блокировкой. Одно исходное
решение даёт не более одного права.

`challenge_appeals`: `appeal_id UUID PRIMARY KEY`, `right_id UUID NOT NULL
UNIQUE FK → challenge_appeal_rights.id`, `state VARCHAR(16) NOT NULL`
(`UNDER_REVIEW`, `UPHELD`, `REJECTED`, `CANCELLED`), `version BIGINT NOT NULL
CHECK > 0`, `submitted_at timestamptz NOT NULL`, `review_due_at timestamptz
NOT NULL`, `cancel_reason VARCHAR(64) NULL`, `cancelled_at timestamptz NULL`,
`created_at`, `updated_at timestamptz NOT NULL`. `CANCELLED` требует причину
и время; другие состояния имеют NULL в этих полях. Принятие, истечение права,
прекращение и применение решения сериализуются через блокировку строки права
и состояния задания/дня; полный порядок блокировок до миграции проверяется.

## Аудитория уведомления

`challenge_notification_audiences`: `id UUID PRIMARY KEY`, `challenge_id UUID
NOT NULL FK → challenges.id`, `source_event_id UUID NOT NULL UNIQUE`,
`max_join_sequence BIGINT NOT NULL CHECK >= 0`, `filter_code VARCHAR(32) NOT
NULL CHECK = 'ALL_JOINED_V1'`, `state VARCHAR(16) NOT NULL`
(`BUILDING`, `SEALED`, `BLOCKED`), `item_count BIGINT NULL CHECK >= 0`,
`digest BYTEA NULL` длиной 32, `cursor_join_sequence BIGINT NOT NULL DEFAULT 0
CHECK >= 0`, `lease_until timestamptz NULL`, `lease_generation BIGINT NOT NULL
DEFAULT 0 CHECK >= 0`, `created_at`, `updated_at timestamptz NOT NULL`.
Для SEALED count и digest обязательны. В момент бизнес-события Challenge под
той же блокировкой challenge сохраняет аудиторию с `max_join_sequence =
next_join_sequence - 1`. При создании `BUILDING` новые участники получают
больший номер и не попадают в этот список. `ALL_JOINED_V1` включает всех
подтверждённо вступивших к моменту факта, включая создателя и выбывших; для
уведомлений о старте, переносе, отмене и результате применяется этот фильтр.
Другие фильтры требуют нового контракта. Участия не удаляются при выбытии.

`challenge_notification_audience_items`: `audience_id UUID NOT NULL FK →
challenge_notification_audiences.id`, `user_id UUID NOT NULL`, `created_at
timestamptz NOT NULL`, PK `(audience_id,user_id)`. Участия и их join_sequence
сохраняются; worker идемпотентно строит страницы без удержания общей длинной
транзакции, проверяет число и digest и переводит аудиторию в SEALED. До SEALED
событие готовой рассылки не публикуется. Уникальный source event не даёт
создать два снимка для одного факта.

Проект digest v1: SHA-256 от UTF-8 `fitness.notification-audience.v1\n` и
строк lowercase UUID получателей по возрастанию UUID-байтов, каждая заканчивается
`\n`; пустой список хеширует только заголовок. Снимок не меняется после SEALED.
Для пагинации используется keyset `user_id > last_user_id` в том же порядке;
cursor v1 — base64url без padding от UTF-8 `v1|<audience_uuid>|<last_user_uuid>`.
Сервис проверяет версию, UUID, соответствие audience и identity Notifications.
Cursor не является авторизацией; пропуск позиций обнаруживается через
count/digest, поэтому завершать рассылку без сверки нельзя. Worker атомарно
берёт lease и увеличивает generation; запись страницы, cursor и подтверждение
SEALED требуют актуального поколения. Потеря lease позволяет повторить страницу
идемпотентно. Для BLOCKED — оповещение и управляемый повтор; BUILDING не
удаляется по TTL. Очистку SEALED до завершения рассылки и закрытия окна replay
запрещаем; окончательная политика хранения и тесты ещё нужны.

## `challenge_access_settings`

Проект: `challenge_id UUID PRIMARY KEY FK → challenges.id`, `password_hash TEXT
NULL`, `invite_key_id VARCHAR(64) NOT NULL`, `created_at timestamptz NOT NULL`,
`updated_at timestamptz NOT NULL`. Строка существует только для `PRIVATE`;
хеш пароля содержит кодировку алгоритма, соль и параметры, исходный пароль не
сохраняется. Токен приглашения восстанавливается сервером через HMAC с ключом из
секретного хранилища по `challenge_id` и `invite_key_id`; в БД нет исходного
токена или ключа. Допустимость приватного доступа при опубликованном состоянии
проверяется локальной транзакцией. Таблица и её секреты не доступны API чтения
для других пользователей; audit не содержит пароля или токена.

## Допуск к модерации: `challenge_review_admissions`

Проект: `submission_id UUID PRIMARY KEY`, `admission_id UUID NOT NULL UNIQUE`,
`participant_id UUID NOT NULL FK → challenge_participants.id`, `task_id UUID
NOT NULL FK → challenge_tasks.id`, `state VARCHAR(16) NOT NULL` (`ADMITTED`,
`REVOKED`), `version BIGINT NOT NULL CHECK > 0`, `asset_id UUID NOT NULL`,
`asset_generation INTEGER NOT NULL CHECK > 0`, `created_at`, `updated_at`
`timestamptz NOT NULL`. `submission_id` и `asset_id` — внешние ID, не FK.
Стабильный `admission_id` создаётся один раз для submission и не меняется при
повторном допуске. Версия увеличивается на один на каждом изменении; новая
генерация файла требует нового перехода с проверкой условий, не замены истории.

Запись допуска/отзыва, локальное состояние задания и outbox сохраняются одним
commit под блокировкой состояния задания участника и допуска. До вставки
отсутствующего допуска сериализация идёт через существующую строку состояния
задания; PK и UNIQUE дополнительно защищают от дубля. Согласование участника,
задания и challenge обязательно; внешние вызовы не выполняются внутри этой
транзакции. Во время апелляции видео следующего дня ждёт согласно
[контракту](../../interactions/44-video-moderation-handoff.md). Решение применяется Challenge
под той же локальной блокировкой только при совпадении ID, версии, submission
и активного допуска. Запоздавшее решение не меняет исход.

## `challenge_inbox` и `challenge_pending_events`

`challenge_inbox`: `consumer VARCHAR(96) NOT NULL`, `event_id UUID NOT NULL`,
`payload_digest BYTEA NOT NULL` с длиной 32 байта, `processed_at timestamptz
NOT NULL`, PK `(consumer,event_id)`. Применение бизнес-изменения, запись inbox
и нового outbox — один commit. Тот же ID с иным digest — карантин.

`challenge_pending_events`: `consumer VARCHAR(96) NOT NULL`, `event_id UUID
NOT NULL`, `event_type VARCHAR(128) NOT NULL`, `schema_version INTEGER NOT NULL
CHECK > 0`, `payload JSONB NOT NULL`, `payload_digest BYTEA NOT NULL` длиной
32 байта, `dependency_type VARCHAR(64) NOT NULL`, `dependency_id UUID NOT NULL`,
`state VARCHAR(16) NOT NULL` (`WAITING`, `PROCESSING`, `APPLIED`,
`QUARANTINED`), `attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`,
`next_attempt_at timestamptz NULL`, `lease_until timestamptz NULL`,
`lease_generation BIGINT NOT NULL DEFAULT 0 CHECK >= 0`, `last_error_code
VARCHAR(64) NULL`, `created_at`, `updated_at timestamptz NOT NULL`, PK
`(consumer,event_id)`; индексы `(state,next_attempt_at)` и
`(dependency_type,dependency_id,state)`.

При отсутствии зависимости сохраняем событие в pending и только после commit
подтверждаем offset. Inbox не помечает его применённым. Повтор проверяет digest;
обработчик с арендой и fencing после появления зависимости атомарно применяет
событие, пишет inbox и `APPLIED`. Интервалы, оповещения и retention ещё
подлежат настройке и согласованию с Kafka.

## `challenge_event_journal`

Проект: `journal_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`,
`event_id UUID NOT NULL UNIQUE`, `event_type VARCHAR(128) NOT NULL`,
`schema_version INTEGER NOT NULL CHECK > 0`, `aggregate_id UUID NOT NULL`,
`payload JSONB NOT NULL`, `payload_digest BYTEA NOT NULL` длиной 32 байта,
`created_at timestamptz NOT NULL`. Бизнес-изменение, журнал и outbox с тем же
event ID фиксируются одним commit; история immutable. `journal_seq` — порядок
страниц после остановки writers, не порядок commit работающих транзакций.
[Стратегия восстановления](../../architecture/recovery/53-event-recovery-and-safe-cleanup.md).

## `challenge_lifecycle_jobs`

`id UUID PRIMARY KEY`, `challenge_id UUID NOT NULL FK → challenges.id`,
`job_kind VARCHAR(32) NOT NULL` (`START_CHECK`, `DAY_DEADLINE`, `UPLOAD_GRACE`,
`APPEAL_DEADLINE`), `subject_id UUID NOT NULL`, `subject_version BIGINT NOT NULL
CHECK > 0`, `due_at timestamptz NOT NULL`, `state VARCHAR(16) NOT NULL`
(`PENDING`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, `BLOCKED`), `attempt_count
INTEGER NOT NULL DEFAULT 0 CHECK >= 0`, `next_attempt_at timestamptz NULL`,
`lease_until timestamptz NULL`, `lease_generation BIGINT NOT NULL DEFAULT 0
CHECK >= 0`, `last_error_code VARCHAR(64) NULL`, `created_at`, `updated_at
timestamptz NOT NULL`, UNIQUE `(job_kind,subject_id,subject_version)`;
индексы `(state,due_at,next_attempt_at)` и `(state,lease_until)`. `subject_id`
идентифицирует локальный challenge, день или право апелляции по виду задачи;
полиморфный FK не предполагается. При создании задачи тип и принадлежность
субъекта проверяются сервером и в тестах.

PostgreSQL — источник истины. Fencing поколения и аренда проверяются после
локальных блокировок при фиксации результата. Срок и текущая версия субъекта
повторно проверяются по времени БД: устаревшая задача завершается без изменения
бизнес-состояния. Переход состояния, audit, outbox и результат job фиксируются
одной локальной транзакцией; внешний gRPC не удерживает БД-транзакцию. Точные
шаги и восстановление: [scheduler](61-challenge-lifecycle-scheduler.md).

## Операции ставок и результат

### Таблицы Challenge

| Таблица | Главные поля и ограничения | Назначение |
| --- | --- | --- |
| `challenge_join_operations` | `id` UUID PK; `challenge_id`, `user_id` UUID NOT NULL; `status` в `PENDING_RESERVE`, `RESERVED`, `CONFIRMED`, `RELEASING`, `FAILED`; `hold_id` UUID NULL; timestamps; индекс `(challenge_id, status)`. | Сохранённая операция до внешнего вызова и её восстановление. Одновременно не более одной незавершённой операции на `(challenge_id,user_id)` — частичный уникальный индекс по незавершённым статусам. |
| `challenge_stake_sets` | `challenge_id` UUID PK; `version` BIGINT NOT NULL; `state` в `COLLECTING`, `DRAINING`, `SEALED`; `seal_operation_id` UUID UNIQUE NULL; `digest` BYTEA NULL; timestamps. | Барьер набора: `DRAINING` запрещает новые join; `SEALED` означает подтверждённое закрытие в Coins. |
| `challenge_stake_set_items` | `challenge_id`, `set_version`, `hold_id`, `user_id`, `amount`; PK `(challenge_id,set_version,hold_id)`; UNIQUE `(challenge_id,set_version,user_id)`; amount > 0. | Сохранённый снимок списка для повторов одной seal-команды. |
| `challenge_release_intents` | `id` UUID PK; `hold_id` UUID NOT NULL UNIQUE; `challenge_id` UUID NOT NULL; `reason` NOT NULL; `status` в `PENDING`, `CONFIRMED`; timestamps. | Один стабильный `release_operation_id` на hold; не менять причину при повторе. |
| `challenge_results` | `id` UUID PK; `challenge_id` UUID NOT NULL UNIQUE; `set_version` BIGINT NOT NULL CHECK > 0; `outcome VARCHAR(32) NOT NULL` в `SINGLE_WINNER`, `MULTIPLE_WINNERS`, `DRAW`; `rule_version VARCHAR(64) NOT NULL`; `winner_count BIGINT NOT NULL CHECK > 0`; `winners_digest BYTEA NOT NULL CHECK octet_length(winners_digest)=32`; `created_at timestamptz NOT NULL`. | Единственный окончательный итог; получатели долей — отдельные строки `challenge_result_winners`. При `DRAW` два получателя не считаются победителями в личной статистике. |
| `challenge_outbox` | `event_id` UUID PK; `event_type`, `schema_version`, `aggregate_id`, `payload`, `created_at`; `published_at` NULL. | Событие и локальное изменение фиксируются одним commit; публикация повторяемая. |

`challenge_result_winners`: `result_id UUID NOT NULL` с локальным FK на
`challenge_results.id`, `user_id UUID NOT NULL`, составной PK
(`result_id`, `user_id`). Участник и результат должны принадлежать одному
challenge: локальная бизнес-проверка перед commit обязательна.
`created_at` результата — серверное время фиксации, возвращаемое внешним API
как `finalized_at`; оно не является временем фактической выплаты Coins. Число,
digest, результат, строки списка и outbox фиксируются вместе; их канонический
формат и условия по исходам — в [контракте расчёта](../coins/34-coins-stake-and-settlement-contract.md#исходы-финального-события-v1).

`challenge_settlement_status`: `challenge_id UUID PRIMARY KEY FK → challenges.id`,
`result_id UUID NOT NULL UNIQUE FK → challenge_results.id`, `settlement_id UUID
NOT NULL UNIQUE`, `state VARCHAR(16) NOT NULL CHECK (state = 'COMPLETED')`,
`completed_at timestamptz NOT NULL`, `source_event_id UUID NOT NULL UNIQUE`,
`updated_at timestamptz NOT NULL`. Проекция только подтверждённого результата
Coins; до получения события отсутствие строки означает неизвестный статус,
а не отсутствие начислений. Совпадение challenge и result проверяется в одной
локальной транзакции. Inbox и эта запись атомарны; конфликт другого settlement
сохраняется для технического разбора и не переписывает факт. Это не ledger и
не источник величины начисления. [Событие Coins](../coins/35-coins-large-settlement.md#событие-завершения-расчёта).

Для outbox `event_type VARCHAR(128) NOT NULL`, `schema_version INTEGER NOT NULL
CHECK > 0`, `aggregate_id UUID NOT NULL`, `payload JSONB NOT NULL`,
`created_at timestamptz NOT NULL`, `published_at timestamptz NULL`; индекс
по неопубликованным записям. Механизм claim, аренды publisher и порядок
публикации одной сущности ещё проектируются; `published_at` сам по себе не
гарантирует наличие события после аварии брокера.

Создание join-операции и перевод набора в `DRAINING` блокируют одну строку
challenge / stake set. Внешние gRPC-вызовы не выполняются внутри длительной
транзакции. Незавершённые вызовы разрешаются по сохранённым идентификаторам;
если результат неизвестен, набор не закрывается. При отмене Challenge создаёт
один release intent на каждый возвращаемый hold и подтверждает возврат только
после Coins. Одновременный итог и отмена взаимно исключаются в Challenge.


## Что осталось до готовности

Нужны формальные схемы и лимиты условий, правила старта и расписания, единая
модель черновика, уникальность активной загрузки, проверка обработки событий и
точный контракт пересчёта состояний. HTTP DTO, коды ответов, авторизация,
ключи идемпотентности и события ещё не определены целиком. Не генерировать
миграции по этому проекту до устранения этих пробелов и сверки с расчётами Coins.
