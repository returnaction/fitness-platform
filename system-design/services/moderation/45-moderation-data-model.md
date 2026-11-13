# Moderation: единый проект модели данных

Это единственное определение проектных таблиц Moderation для будущего Java /
Spring-сервиса; оно не является миграцией и не описывает текущую Python-БД.
Challenge владеет правом апелляции, Video Proof — файлами и техническими
доказательствами, Identity — идентичностью и назначением прав. Moderation не
изменяет таблицы других сервисов. Служебные UUID других сервисов не являются FK.

Во всех таблицах UUID — идентификаторы, `timestamptz` — UTC, временные метки
серверные. Все ограничения и статусы ниже — проект, требующий тестирования.
`created_at` и `updated_at` — NOT NULL там, где указаны. Тексты и JSON не
должны содержать токены, подписанные URL или пароли.

## `moderation_review_cases`

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `id` | UUID PK | Карточка проверки упражнения. |
| `submission_id`, `task_id` | UUID NOT NULL | Внешние ID отправки и задания. |
| `admission_id` | UUID NOT NULL | Стабильный ID допуска от Challenge. |
| `admission_version` | BIGINT NOT NULL CHECK > 0 | Проверяемая версия допуска. |
| `asset_id` | UUID NOT NULL | Внешний ID playback. |
| `asset_generation` | INTEGER NOT NULL CHECK > 0 | Версия playback. |
| `task_snapshot` | JSONB NOT NULL | Снимок задания из доверенного события Challenge; схема ещё версионируется. |
| `state` | VARCHAR(32) NOT NULL | `OPEN`, `IN_REVIEW`, `DECIDED`, `CANCELLED`. |
| `version` | BIGINT NOT NULL CHECK > 0 | Защита конкурентных переходов. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Создание и обновление. |

UNIQUE `(submission_id, admission_version)`; частичный UNIQUE `(submission_id)`
для `state IN ('OPEN','IN_REVIEW')` запрещает две активные карточки. Повтор
события той же версии допуска не создаёт вторую карточку.
При конфликтующих данных — карантин, не обновление существующей карточки.
Повторный допуск той же записи с другой версией и отзыв допуска ещё требуют
проверки совместимости с Challenge и обработки гонок. Не включать nickname, ставку и название
challenge в снимок для модератора.

## `moderation_review_assignments`

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `id` | UUID PK | Назначение. |
| `case_id` | UUID NOT NULL FK → `moderation_review_cases.id` | Карточка. |
| `moderator_user_id` | UUID NOT NULL | Identity ID сотрудника. |
| `state` | VARCHAR(16) NOT NULL | `ACTIVE`, `RELEASED`, `COMPLETED`. |
| `assigned_at` | timestamptz NOT NULL | Время назначения. |
| `ended_at` | timestamptz NULL | Время завершения назначения. |

Частичный UNIQUE `(case_id) WHERE state = 'ACTIVE'`; состояние и `ended_at`
согласованы CHECK. Назначение и решение сериализуются по строке дела.
Роль проверяется по актуальным полномочиям, а не только по наличию назначения.
Принцип возврата незавершённого дела в очередь выбран; сроки и механизм его
автоматического истечения ещё не определены.

## `moderation_review_decisions`

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `id` | UUID PK | ID решения. |
| `case_id` | UUID NOT NULL UNIQUE FK → `moderation_review_cases.id` | Одно исходное решение по карточке. |
| `assignment_id` | UUID NOT NULL FK → `moderation_review_assignments.id` | Назначение на момент решения. |
| `moderator_user_id` | UUID NOT NULL | Служебный автор решения. |
| `verdict` | VARCHAR(40) NOT NULL | `APPROVE`, `DISAPPROVE_TECHNIQUE`, `DISAPPROVE_WRONG_EXERCISE_OR_REPS`, `REJECT_UNSUITABLE_CONTENT`. |
| `reason` | TEXT NULL | Для всех решений кроме `APPROVE` обязательна непустая причина. |
| `admission_id` | UUID NOT NULL | Копия проверяемого допуска; должна совпадать с карточкой. |
| `admission_version` | BIGINT NOT NULL CHECK > 0 | Версия допуска на момент решения. |
| `created_at` | timestamptz NOT NULL | Время решения. |

Запись неизменяема; сохранение решения, `DECIDED`, результата идемпотентного
запроса и outbox — одна транзакция. Атомарно проверять активное назначение,
роль, состояние карточки и её версию. Ответ Challenge об актуальности допуска
может поступить позже: устаревшее решение хранится в истории, но Challenge его
не применяет. Доступные исходы и апелляция:
[решения](25-moderation-decisions.md#согласованные-коды-решений-и-состояние-задания).

## `moderation_appeal_cases`

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `id` | UUID PK | Локальный ID карточки апелляции. |
| `appeal_id` | UUID NOT NULL UNIQUE | ID поданной апелляции от Challenge. |
| `review_decision_id` | UUID NOT NULL FK → `moderation_review_decisions.id` | Исходное решение. |
| `state` | VARCHAR(24) NOT NULL | `UNDER_REVIEW`, `UPHELD`, `REJECTED`, `CANCELLED`. |
| `cancel_reason` | VARCHAR(64) NULL | Для `CANCELLED` обязательно `OTHER_TASK_FINAL_FAILED`; иначе NULL. |
| `cancelled_at` | timestamptz NULL | Для `CANCELLED` обязательно серверное время Challenge; иначе NULL. |
| `submitted_at`, `review_due_at` | timestamptz NOT NULL | Серверный момент принятия Challenge и цель +24 часа. |
| `version` | BIGINT NOT NULL CHECK > 0 | Версия карточки. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время локальной записи. |

Право подачи и атомарная проверка срока принадлежат Challenge. Повтор события
по `appeal_id` не создаёт вторую карточку; апелляцию нельзя отклонять лишь из-за
задержки доставки Kafka. Истечение `review_due_at` только сигнализирует о
просрочке, не создаёт решение. Запрет второй апелляции к одному исходному
решению должен обеспечиваться владельцем права — Challenge; локально нужен
уникальный индекс по исходному решению, если контракт допускает только одно дело.

`CANCELLED` — прекращение, а не вердикт модератора. Событие, пришедшее до
карточки апелляции, сохраняется в guard ниже; поздняя подача не открывает её.
Решение, уже сохранённое до доставки отмены, остаётся в журнале, но может не
примениться в Challenge при проигранной гонке. Закрытие активного назначения
идёт в том же commit.

## `moderation_appeal_guards`

`appeal_id UUID PRIMARY KEY`, `submission_id UUID NOT NULL`, `version BIGINT
NOT NULL CHECK > 0`, `state VARCHAR(16) NOT NULL` (`SUBMITTED`, `CANCELLED`),
`payload_digest BYTEA NOT NULL` длиной 32 байта, `updated_at timestamptz NOT
NULL`. При первом событии безопасный insert и блокировка строки; поздний
`SUBMITTED` меньшей версии после `CANCELLED` игнорируется как устаревший,
противоречивое содержание одной версии — карантин. В v1 guard автоматически не
удаляется в течение существования бизнес-истории; возможная будущая очистка
требует подтверждённой границы replay и отдельной политики. Единое правило:
[очистка и восстановление](../../architecture/recovery/53-event-recovery-and-safe-cleanup.md#решение-по-очистке).

## `moderation_appeal_assignments` и `moderation_appeal_decisions`

`moderation_appeal_assignments`: `id UUID PK`, `case_id UUID NOT NULL FK →
moderation_appeal_cases.id`, `moderator_user_id UUID NOT NULL`,
`state VARCHAR(16) NOT NULL` (`ACTIVE`, `RELEASED`, `COMPLETED`),
`assigned_at timestamptz NOT NULL`, `ended_at timestamptz NULL`; частичный
UNIQUE `(case_id) WHERE state='ACTIVE'`. Состояние и ended_at согласованы CHECK.

`moderation_appeal_decisions`: `id UUID PK`, `case_id UUID NOT NULL UNIQUE FK →
moderation_appeal_cases.id`, `assignment_id UUID NOT NULL FK →
moderation_appeal_assignments.id`, `moderator_user_id UUID NOT NULL`,
`outcome VARCHAR(16) NOT NULL` (`UPHELD`, `REJECTED`), `reason TEXT NOT NULL`
с непустой причиной, `created_at timestamptz NOT NULL`. Неизменяемая запись.

В одной транзакции под блокировкой карточки проверить актуальное назначение и
право `appeal.decide`, отсутствие решения и то, что автор не принимал исходное
решение. Сравнение исходного автора выполняет Moderation по локальному FK.
Сохранить решение, статус, идемпотентный результат и outbox; пересмотр сохраняет
отдельную историю. Не доверять переданному клиентом ID модератора.

## `moderation_technical_cases`

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `id` | UUID PK | ID дела. |
| `submission_id` | UUID NOT NULL | Внешняя запись Video Proof. |
| `trigger_id` | UUID NOT NULL UNIQUE | Стабильный ID причины от Video Proof; повтор не создаёт дело. |
| `reason_code` | VARCHAR(64) NOT NULL | Техническая причина, не обвинение. |
| `evidence_ref` | JSONB NOT NULL | Идентификаторы версий доказательств, без URL и секретов; версия схемы требуется. |
| `state` | VARCHAR(24) NOT NULL | `OPEN`, `IN_REVIEW`, `ESCALATED`, `RESOLVING`, `CLOSED`. |
| `version` | BIGINT NOT NULL CHECK > 0 | Версия дела. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время. |

`trigger_id` — первый сигнал, открывший дело. Частичный уникальный индекс
`(submission_id) WHERE state IN ('OPEN','IN_REVIEW','ESCALATED','RESOLVING')`
допускает одно активное техническое дело на отправку. Новый trigger привязывается
к активному делу или создаёт новое, если активного нет; при гонке создания
обработчик после конфликта уникальности повторно читает победившую строку.
Закрытые дела остаются в истории.

## `moderation_technical_triggers`

`trigger_id UUID PRIMARY KEY`, `case_id UUID NOT NULL FK →
moderation_technical_cases.id`, `submission_id UUID NOT NULL`, `reason_code
VARCHAR(64) NOT NULL`, `evidence_ref JSONB NOT NULL`, `payload_digest BYTEA
NOT NULL CHECK octet_length(payload_digest)=32`, `state VARCHAR(16) NOT NULL
CHECK IN ('OPEN','RESOLVED')`, `created_at timestamptz NOT
NULL`; индекс `(case_id,created_at,trigger_id)`. `submission_id` совпадает с
делом: локальный составной FK либо проверка под блокировкой. Повтор trigger с
тем же содержанием не создаёт запись; отличающийся payload — карантин. Inbox,
trigger, изменение дела и аудит — один commit. Новый trigger не меняет
автоматически предыдущее решение или назначение.

`moderation_technical_decision_triggers`: `decision_id UUID NOT NULL FK →
moderation_technical_decisions.id`, `trigger_id UUID NOT NULL FK →
moderation_technical_triggers.trigger_id`, PRIMARY KEY `(decision_id,trigger_id)`.
Под блокировкой дела при `RETRY_AFTER_FIX` фиксируется набор относящихся к
решению открытых сигналов; это не снимает их до результата повтора. При
подтверждённом `SUCCEEDED` только сигналы этого набора получают `RESOLVED` в
той же транзакции, что обработка события. Новые сигналы остаются `OPEN`;
`INCONCLUSIVE` не разрешает сигналы. Допустимость связей одного дела
обеспечивается локальными ограничениями либо проверкой транзакции.

## `moderation_technical_assignments` и `moderation_technical_decisions`

`moderation_technical_assignments`: `id UUID PK`, `case_id UUID NOT NULL FK →
moderation_technical_cases.id`, `reviewer_user_id UUID NOT NULL`,
`state VARCHAR(16) NOT NULL` (`ACTIVE`, `RELEASED`, `COMPLETED`),
`assigned_at timestamptz NOT NULL`, `ended_at timestamptz NULL`; частичный
UNIQUE `(case_id) WHERE state='ACTIVE'`; время и состояние согласованы CHECK.

`moderation_technical_decisions`: `id UUID PK`, `case_id UUID NOT NULL FK →
moderation_technical_cases.id`, `assignment_id UUID NOT NULL FK →
moderation_technical_assignments.id`, `reviewer_user_id UUID NOT NULL`,
`case_version BIGINT NOT NULL CHECK > 0`, `decision VARCHAR(32) NOT NULL`
(`INCONCLUSIVE`, `RETRY_AFTER_FIX`, `CONFIRMED_TAMPERING`),
`reason TEXT NOT NULL` и непустой, `evidence_ref JSONB NOT NULL`,
`created_at timestamptz NOT NULL`; UNIQUE `(case_id, case_version)`.

`INCONCLUSIVE` — промежуточное решение; следующее является новой записью.
`CONFIRMED_TAMPERING` требует отдельного полномочия ответственного и
обоснования, а не только несовпадения хеша. Различать решение о запуске повтора
и результат повторной обработки; оба сохраняются без перезаписи истории.
Переход после результата Video Proof описан в [событиях технического разбора](../../interactions/51-technical-review-events.md).
Доказательство v1 — объект `{"schemaVersion":1,"assessmentId":"<uuid>"}`;
подробности читаются у Video Proof по полномочию, не копируются в событие.

## `moderation_idempotency_requests`

`id UUID PK`, `actor_user_id UUID NOT NULL`, `operation VARCHAR(96) NOT NULL`,
`key_digest BYTEA NOT NULL`, `request_fingerprint BYTEA NOT NULL`,
`state VARCHAR(16) NOT NULL` (`PENDING`, `COMPLETED`), `response_status INTEGER
NULL`, `response_body JSONB NULL`, `created_at`, `updated_at timestamptz NOT
NULL`; UNIQUE `(actor_user_id, operation, key_digest)`. Для `COMPLETED` статус
и безопасное тело обязательны. Строка, бизнес-решение и outbox фиксируются
одним commit. Отпечаток учитывает ID цели и канонизированный payload; ключ и
секреты в ответ/логи не включаются. Завершённый результат хранится минимум
90 суток; размер ответов и защита отпечатка ещё определяются.

## `moderation_event_journal`

Проект: `journal_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`,
`event_id UUID NOT NULL UNIQUE`, `event_type VARCHAR(128) NOT NULL`,
`schema_version INTEGER NOT NULL CHECK > 0`, `aggregate_id UUID NOT NULL`,
`payload JSONB NOT NULL`, `payload_digest BYTEA NOT NULL` длиной 32 байта,
`created_at timestamptz NOT NULL`. Изменение, журнал и outbox сохраняются одним
commit; история immutable. Sequence — ключ страниц при остановленных writers,
не гарантированный порядок commit одновременно идущих транзакций.
[Стратегия восстановления](../../architecture/recovery/53-event-recovery-and-safe-cleanup.md).

## `moderation_inbox`, `moderation_outbox`, `moderation_audit`

`moderation_inbox`: `consumer VARCHAR(96) NOT NULL`, `event_id UUID NOT NULL`,
`payload_digest BYTEA NOT NULL`, `processed_at timestamptz NOT NULL`, PK
`(consumer,event_id)`. Inbox и локальное действие — один commit; одинаковый ID
с другим digest отправляется на разбор. При недостающей зависимости событие
не помечается применённым без устойчивой записи ожидания.

## `moderation_admission_guards` — проект

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `submission_id` | UUID PK | Одна запись Challenge для одной отправки. |
| `admission_id` | UUID NOT NULL UNIQUE | Стабильный ID допуска отправки. |
| `highest_version` | BIGINT NOT NULL CHECK > 0 | Наибольшая подтверждённая версия. |
| `state` | VARCHAR(16) NOT NULL | `ADMITTED` или `REVOKED`. |
| `payload_digest` | BYTEA NOT NULL, 32 байта | Digest нормализованного содержания состояния данной версии. |
| `updated_at` | timestamptz NOT NULL | Серверное время. |

В проекте v1 `admission_id` **стабилен на весь жизненный цикл submission**;
отзыв и повторный допуск используют следующий номер версии с тем же ID.
Событие с другим `admission_id` для того же submission или тот же ID для
другого submission — конфликт на разбор, не замена guard. При создании guard
конкуренцию сериализуют через уникальный PK и безопасный insert/retry, затем
блокируют строку. Одну и ту же версию с противоречивым state/payload не
перезаписывают. Отзыв, пришедший первым, создаёт `REVOKED` guard — tombstone.
В v1 guard автоматически не удаляется в течение существования бизнес-истории.
Возможная будущая очистка зависит от Kafka, архива и максимального replay:
[единая политика](../../architecture/recovery/53-event-recovery-and-safe-cleanup.md#решение-по-очистке).

Частичный UNIQUE активной карточки, определённый выше, не заменяет guard или
проверку версии.
Повторный допуск после отзыва может создать новую карточку только при новой
версии guard, закрытой предыдущей карточке и отдельном бизнес-ключе
`(submission_id, admission_version)`. Применять этот проект к миграции только
после синхронизации схемы Challenge. Исходное
решение старой карточки не перезаписывается.

## `moderation_pending_events` — проект

| Поле | Тип и ограничение | Назначение |
| --- | --- | --- |
| `consumer` | VARCHAR(96) NOT NULL | Получатель события. |
| `event_id` | UUID NOT NULL | Событие; PK `(consumer,event_id)`. |
| `event_type` | VARCHAR(128) NOT NULL | Тип события. |
| `schema_version` | INTEGER NOT NULL CHECK > 0 | Версия схемы. |
| `payload` | JSONB NOT NULL | Валидированное событие без секретов и URL. |
| `payload_digest` | BYTEA NOT NULL, 32 байта | Проверка совпадения повтора. |
| `dependency_type` | VARCHAR(64) NOT NULL | Тип отсутствующего факта. |
| `dependency_id` | UUID NOT NULL | Идентификатор зависимости. |
| `state` | VARCHAR(16) NOT NULL | `WAITING`, `PROCESSING`, `APPLIED`, `QUARANTINED`. |
| `attempt_count` | INTEGER NOT NULL DEFAULT 0 CHECK >= 0 | Фактические попытки применения. |
| `next_attempt_at`, `lease_until` | timestamptz NULL | Повтор и аренда обработчика. |
| `lease_generation` | BIGINT NOT NULL DEFAULT 0 CHECK >= 0 | Fencing поколения обработчика. |
| `last_error_code` | VARCHAR(64) NULL | Служебный код без секретов. |
| `created_at`, `updated_at` | timestamptz NOT NULL | Время состояния. |

Индексы `(state,next_attempt_at)` и `(dependency_type,dependency_id,state)`.
При недостающей зависимости consumer сохраняет полный валидированный payload и
digest в pending одной транзакцией; только после commit можно подтвердить Kafka
offset. Inbox при этом **не помечает событие применённым**. Повтор того же
event_id сверяет pending digest и не создаёт дубликат; другой digest — карантин.
Фоновый обработчик с арендой и fencing повторяет обработку при появлении
зависимости: применение бизнеса, inbox и `APPLIED` фиксируются одним commit.
При сбое аренда истекает и работа восстанавливается. Постоянно отсутствующую
зависимость не удаляют и не повторяют бесконечно без наблюдения: ограниченные
повторы, сигнал ответственному и управляемая повторная обработка. Точные
интервалы и сроки хранения связаны с retention Kafka и ещё не утверждены.

`moderation_outbox`: `event_id UUID PK`, `event_type VARCHAR(128) NOT NULL`,
`schema_version INTEGER NOT NULL CHECK > 0`, `aggregate_id UUID NOT NULL`,
`payload JSONB NOT NULL`, `created_at timestamptz NOT NULL`,
`published_at timestamptz NULL`; частичный индекс неопубликованных событий.
Доставка Kafka повторяема; `published_at` не является заменой настройкам
надёжности брокера. Порядок и повтор при сбоях проектируются отдельно.

`moderation_audit`: `id UUID PK`, `actor_user_id UUID NULL`, `actor_type
VARCHAR(24) NOT NULL`, `action VARCHAR(96) NOT NULL`, `object_type VARCHAR(64)
NOT NULL`, `object_id UUID NOT NULL`, `request_id UUID NULL`, `details JSONB
NOT NULL`, `occurred_at timestamptz NOT NULL`; индекс `(object_type, object_id,
occurred_at)`. Успешное решение/назначение и аудит фиксируются вместе;
аудит отказов требует отдельного устойчивого пути. Не сохранять URL, токены,
данные участника сверх необходимого. Минимальный срок хранения аудита после
окончательного закрытия дела — год; политика доступа и удаления ещё открыта.

## Перед созданием миграций

Уточнить типы и пределы всех строк/JSON, согласование внешних событий,
ограничения активных дел при повторном допуске, назначение нескольких ролей,
отзыв и задержанный порядок событий, immutable
enforcement, реализацию согласованных сроков и retention событий. Выполнить PostgreSQL-
тесты конкурентных назначений/решений, отказов и rollback и интеграционные
сценарии с Kafka. Этот документ не означает, что проверки выполнены.
