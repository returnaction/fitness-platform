# Coins: единая модель данных

Это единственный проектный источник определений таблиц Coins для системы Java / Spring. Сценарии и API находятся в отдельных документах и ссылаются сюда. Текущая Python-БД может отличаться; этот документ не является миграцией. Таблицы Challenge принадлежат Challenge и остаются в его контракте. Каждую таблицу описываем здесь один раз; не копируем её определения в другие файлы.

## Кошелёк, резервы, журнал и награда

### `coin_wallets`

Внутренний вызов Identity `CreateWalletIfAbsent` описан в [контракте регистрации](../identity/30-identity-registration.md#внутренний-контракт-создания-профиля-и-кошелька).

| Поле | Тип и правило |
| --- | --- |
| `user_id` | UUID PK; один кошелёк на пользователя. |
| `available_balance`, `locked_balance` | BIGINT NOT NULL DEFAULT 0, CHECK >= 0. |
| `created_at`, `updated_at` | timestamptz NOT NULL. |

Создание идемпотентно по `user_id`, повтор не обнуляет существующий кошелёк.
Значения целые; переполнение BIGINT проверяется до обновления. Хранимый баланс
и изменения ledger изменяются одной транзакцией; журнал не заменяет проверку
текущего баланса под блокировкой.

### `coin_holds`

| Поле | Тип и правило |
| --- | --- |
| `id` | UUID PK, идентификатор резерва. |
| `user_id` | UUID NOT NULL, локальный FK на кошелёк с RESTRICT. |
| `challenge_id` | UUID NOT NULL, внешняя бизнес-ссылка, не FK. |
| `join_operation_id` | UUID NOT NULL UNIQUE, идентификатор операции Challenge. |
| `amount` | BIGINT NOT NULL CHECK > 0. |
| `status` | `ACTIVE`, `RELEASED`, `SETTLED`; разрешённые переходы определяются правилами операции. |
| `created_at`, `updated_at` | timestamptz NOT NULL. |

Предлагается частичный уникальный индекс (`challenge_id`, `user_id`) WHERE
`status = 'ACTIVE'`: он запрещает два активных резерва и позволяет новую
операцию после освобождения, сохраняя историю. `join_operation_id` уникален
для всех состояний. Индекс не доказывает право повторного вступления — это
проверяет Challenge. Нулевые и отрицательные ставки запрещены. При неизвестном ответе нельзя
освобождать резерв, пока результат участия не выяснен. Окончательные состояния
и состав записей распределения дополнительно уточняются при моделировании итога.

### `coin_ledger_entries`

| Поле | Тип и правило |
| --- | --- |
| `id` | UUID PK. |
| `user_id` | UUID NOT NULL, локальный FK на кошелёк с RESTRICT. |
| `operation_id` | UUID NOT NULL, идентификатор бизнес-операции. |
| `entry_index` | INTEGER NOT NULL, номер записи внутри операции; UNIQUE (`operation_id`, `entry_index`). |
| `type` | строка NOT NULL: награда, резерв, возврат, распределение или корректировка; точный каталог версионируется. |
| `available_delta`, `locked_delta` | BIGINT NOT NULL, знаковые изменения каждого баланса. |
| `challenge_id` | UUID NULL, ссылка по смыслу без межбазового FK. |
| `reverses_entry_id` | UUID NULL, локальный FK на исправляемую запись; правила повторных исправлений определяются отдельно. |
| `created_at` | timestamptz NOT NULL. |

Записи только добавляются; права БД и сервис не допускают обычного UPDATE / DELETE.
Индекс (`user_id`, `created_at`, `id`) поддерживает стабильную историю. Delta
позволяет различать перемещение в резерв и начисление: резерв 10 — `-10` и
`+10`, ежедневная награда — `+10` и `0`. Идентификатор операции и атомарная
проверка повторов нужны дополнительно: один `event_id` не защищает от разных
событий об одной бизнес-операции.

### `daily_reward_claims`

| Поле | Тип и правило |
| --- | --- |
| `id` | UUID PK. |
| `user_id` | UUID NOT NULL, локальный FK на кошелёк. |
| `claim_date` | DATE NOT NULL, дата по UTC. |
| `claimed_at` | timestamptz NOT NULL, серверное время. |
| `amount` | BIGINT NOT NULL CHECK > 0; для текущего правила 10. |
| `ledger_entry_id` | UUID NOT NULL UNIQUE, локальный FK на запись начисления. |

UNIQUE (`user_id`, `claim_date`) и индекс (`user_id`, `claimed_at`); в одной
транзакции блокируем кошелёк, проверяем дату UTC и 24 часа с последнего claim,
создаём claim и ledger и обновляем баланс. Используется серверное время UTC;
вызов Accounts и timezone пользователя для начисления не нужны.

### `daily_reward_requests`: предложенная таблица повторов

| Поле | Тип и правило |
| --- | --- |
| `id` | UUID PK, идентификатор операции в Coins. |
| `user_id` | UUID NOT NULL, локальный FK на кошелёк. |
| `key_digest` | bytes NOT NULL, защищённое представление ключа; UNIQUE (`user_id`, `key_digest`). |
| `outcome` | `GRANTED` или `ALREADY_CLAIMED`; итоговый результат на момент первого запроса. |
| `claim_id` | UUID NOT NULL, локальный FK на соответствующий claim. |
| `response_snapshot` | JSONB NOT NULL, сохранённый безопасный ответ без токенов и секретов. |
| `created_at` | timestamptz NOT NULL. |

Ключ — случайный UUID v4, создаётся клиентом на одно нажатие и сохраняется до
получения результата. Сервер валидирует формат, хранит SHA-256 от канонического
значения UUID, не записывает исходный ключ в логи; UUID создаётся криптографически
стойким генератором. Уникальность — (`user_id`, digest). Ключ — идентификатор,
а не пароль или самостоятельное разрешение; авторизация пользователя обязательна.

Сохранение результата, claim, ledger и кошелька выполняется в одной транзакции
Coins. Для `ALREADY_CLAIMED` нового claim и новой записи ledger нет. Временный
сбой до commit откатывает всю транзакцию и не фиксирует ложный успех. Сервер
проверяет ключ в пределах конкретного пользователя и этой операции. Индекс,
валидация UUID и блокировки проверяются на PostgreSQL.


## Набор ставок и обмен событиями

### `coin_event_journal`

Проект: `journal_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`,
`event_id UUID NOT NULL UNIQUE`, `event_type VARCHAR(128) NOT NULL`,
`schema_version INTEGER NOT NULL CHECK > 0`, `aggregate_id UUID NOT NULL`,
`payload JSONB NOT NULL`, `payload_digest BYTEA NOT NULL` длиной 32 байта,
`created_at timestamptz NOT NULL`. Неизменяемый факт Coins, соответствующее
изменение ledger/состояния и outbox сохраняются одной локальной транзакцией.
После подтверждённой остановки writers sequence используется для постраничной
сверки, но не считается порядком commit конкурентных транзакций.

### `coin_pending_events`

Проект: `consumer VARCHAR(96) NOT NULL`, `event_id UUID NOT NULL`,
`event_type VARCHAR(128) NOT NULL`, `schema_version INTEGER NOT NULL CHECK > 0`,
`payload JSONB NOT NULL`, `payload_digest BYTEA NOT NULL` длиной 32 байта,
`dependency_type VARCHAR(64) NOT NULL`, `dependency_id UUID NOT NULL`,
`state VARCHAR(16) NOT NULL` (`WAITING`, `PROCESSING`, `APPLIED`, `QUARANTINED`),
`attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`, `next_attempt_at
timestamptz NULL`, `lease_until timestamptz NULL`, `lease_generation BIGINT
NOT NULL DEFAULT 0 CHECK >= 0`, `last_error_code VARCHAR(64) NULL`,
`created_at`, `updated_at timestamptz NOT NULL`, PK `(consumer,event_id)`;
индексы `(state,next_attempt_at)`, `(dependency_type,dependency_id,state)`.
Если зависимость отсутствует, payload и digest сохраняются до подтверждения
offset; событие не помечается применённым в inbox. При появлении зависимости
обработчик с арендой и fencing фиксирует применение, inbox и APPLIED одним
commit. Повтор другого payload для того же ID — карантин. Невыполненное
ожидание не очищается автоматически; журнал событий не заменяет ledger.

### Таблицы Coins

Кошелёк, hold, ledger и ежедневный claim описаны выше. Подробнее о сценариях в
[модели Coins](33-coins-wallet-and-reward.md). Дополняем их:

| Таблица | Главные поля и ограничения | Назначение |
| --- | --- | --- |
| `coin_stake_sets` | `challenge_id` UUID PK; `state` в `OPEN`, `SEALED`, `CANCEL_DRAINING`, `SETTLED`, `CANCELLED`; `cancellation_id` UUID NULL; `set_version` BIGINT NULL; `digest` BYTEA NULL; timestamps. | Одна строка блокировки для reserve, seal, отмены и финальной операции по challenge. |
| `coin_stake_operations` | составной PK (`kind`, `id` UUID); `kind` в `RESERVE`, `RELEASE`, `SEAL`, `FREEZE`, `COMPLETE_CANCELLATION`; `challenge_id` UUID NOT NULL; `request_hash` BYTEA NOT NULL; `result` JSONB NOT NULL; `created_at`. | Идемпотентный исход, включая отказ; один ID одного вида с другими параметрами — конфликт. FREEZE и COMPLETE могут использовать один cancellation_id. |
| `coin_sealed_stakes` | `challenge_id`, `set_version`, `hold_id` UUID, `user_id` UUID, `amount` BIGINT > 0; PK `(challenge_id,set_version,hold_id)`; локальный FK на `coin_holds.id`. | Неизменяемый проверенный снимок. |
| `coin_inbox` | `consumer`, `event_id` UUID; PK `(consumer,event_id)`; `payload_hash`, `processed_at` NOT NULL. | Дедупликация доставки с проверкой совпадения данных. |
| `coin_outbox` | `event_id` UUID PK; тип, версия, payload, `created_at`; `published_at` NULL. | Событие сохраняется в той же локальной транзакции, что соответствующее изменение состояния. |
| `coin_challenge_projection` | `challenge_id` UUID PK; `title` строка NOT NULL; `source_version` BIGINT NOT NULL; `updated_at` NOT NULL. | Локальный доверенный снимок названия для истории. |

Типы и инварианты для дополнительных таблиц: `kind`, `status`, `state` и
`outcome` — `VARCHAR(32) NOT NULL` с CHECK на допустимый каталог v1;
`request_hash`, `payload_hash`, `digest` — `BYTEA` длиной 32 байта; `result`,
`payload` — `JSONB`; денежные поля `BIGINT` с CHECK на допустимый диапазон.
`rule_version` — `VARCHAR(64) NOT NULL`, `event_type` — `VARCHAR(128) NOT NULL`,
`schema_version` — `INTEGER NOT NULL CHECK > 0`. `created_at`, `updated_at`,
`processed_at` — `timestamptz NOT NULL`, `published_at` — nullable.
Допустимость NULL `digest` и `set_version` зависит от состояния и должна быть
закреплена CHECK в миграции: в `OPEN` оба NULL, после успешного seal — оба
NOT NULL; `CANCEL_DRAINING` / `CANCELLED` допускают обе согласованные пары,
поскольку отмена возможна до или после seal. `cancellation_id` обязателен в
`CANCEL_DRAINING` / `CANCELLED`. В `coin_settlement_allocations` записи
создаются только для победителей; `remainder_bonus` равен 0 или 1. Выплата
проигравшего равна 0. `pool` — NOT NULL CHECK > 0. Эти ограничения не заменяют проверки
соответствия полного набора, единственности результата и сохранения суммы.

Переход резерва: `ACTIVE` → `RELEASED` при возврате либо `ACTIVE` → `SETTLED`
при окончательном расчёте. Задействованные строки блокируются по единому
[порядку Coins](35-coins-large-settlement.md#порядок-блокировок-coins); его
необходимо проверить против deadlock во всех операциях. Каждый
шаг выплаты и его ledger фиксируются одной локальной транзакцией; inbox создаёт
долговечную задачу, а outbox финала фиксируется при полном завершении.
Частичную выплату нельзя считать успешным полным расчётом.


### Дополнительные таблицы Coins

`coin_stake_seal_uploads`: `seal_operation_id UUID PRIMARY KEY`, `challenge_id
UUID NOT NULL`, `set_version BIGINT NOT NULL CHECK > 0`, `expected_count BIGINT
NOT NULL CHECK > 0`, `expected_digest BYTEA NOT NULL CHECK octet_length=32`,
`status VARCHAR(16) NOT NULL CHECK IN ('UPLOADING','SEALED')`, `created_at` и
`updated_at timestamptz NOT NULL`. Частичный UNIQUE на `challenge_id` при
`status='UPLOADING'`; повтор после SEALED определяется по сохранённому ID.

`coin_stake_seal_batches`: `seal_operation_id UUID` FK на uploads, `batch_number
INTEGER CHECK >= 0`, `payload_digest BYTEA NOT NULL` длиной 32, `item_count
SMALLINT NOT NULL CHECK BETWEEN 1 AND 500`; PK (`seal_operation_id`,
`batch_number`). Данные записываются вместе со строками пакета в транзакции.

`coin_stake_seal_items`: `seal_operation_id UUID` FK на uploads, `batch_number
INTEGER NOT NULL`, `hold_id UUID NOT NULL`, `user_id UUID NOT NULL`, `amount
BIGINT NOT NULL CHECK > 0`; PK (`seal_operation_id`, `hold_id`), UNIQUE
(`seal_operation_id`, `user_id`), составной FK на соответствующий batch.
Сверка во время финализации учитывает также все активные holds, не только
загруженные строки. Cleanup незавершённой upload-операции не выполняется по
таймеру без согласования с Challenge: иначе повтор мог бы потерять свой смысл.


## Таблицы Coins: дополнение

| Таблица | Основные поля и ограничения | Значение |
| --- | --- | --- |
| `coin_settlements` | `id UUID PK`, `result_id UUID UNIQUE`, `challenge_id UUID UNIQUE`, `set_version BIGINT`, `rule_version VARCHAR(64)`, `pool BIGINT CHECK > 0`, `phase VARCHAR(24)`, `winner_count BIGINT CHECK > 0`, `winners_digest BYTEA` длиной 32, `winners_cursor TEXT NULL`, `winners_loaded BIGINT DEFAULT 0 CHECK >= 0`, `random_seed BYTEA NULL`, `blocked_from_phase VARCHAR(24) NULL`, `created_at`, `updated_at`; кроме отмеченных NULL поля обязательны. | Долговечная задача: `READING_WINNERS`, `PREPARING`, `COLLECTING`, `DISTRIBUTING`, `COMPLETED`, `BLOCKED`. При BLOCKED сохраняется предыдущая фаза для контролируемого восстановления. `random_seed` после создания неизменен. |
| `coin_settlement_winners` | `settlement_id UUID` FK, `user_id UUID`, `random_rank BYTEA NULL` длиной 32 при наличии; PK `(settlement_id,user_id)`, индекс `(settlement_id,random_rank,user_id)` | Загруженные победители; rank назначается после фиксации seed; прогресс cursor и сверка digest должны переживать сбой. |
| `coin_settlement_allocations` | `settlement_id UUID` FK, `user_id UUID`, `amount BIGINT CHECK >= 0`, `remainder_bonus SMALLINT CHECK IN (0,1)`, `status` в `PENDING`, `PAID`, PK `(settlement_id,user_id)` | Зафиксированная выплата победителю. |
| `coin_settlement_hold_moves` | `settlement_id UUID` FK, `hold_id UUID` локальный FK, `amount BIGINT CHECK > 0`, PK `(settlement_id,hold_id)` | Один проведённый hold на расчёт. |
| `coin_settlement_escrows` | `settlement_id UUID PK` FK, `balance BIGINT CHECK >= 0`, `updated_at timestamptz` | Внутренний остаток банка. |
| `coin_settlement_escrow_entries` | `id UUID PK`, `settlement_id UUID` FK, `operation_id UUID`, `wallet_ledger_entry_id UUID` локальный FK UNIQUE на ledger, `delta BIGINT CHECK <> 0`, `created_at`, UNIQUE `(settlement_id,operation_id)` | Неизменяемая история escrow; связанная запись wallet ledger создаётся в той же транзакции. |

Кроме явно указанных NULL поля обязательны; статусы ограничиваются CHECK.
`coin_settlements` дополнительно содержит `lease_owner UUID NULL`, `lease_until
timestamptz NULL`, `lease_version BIGINT NOT NULL DEFAULT 0 CHECK >= 0`,
`next_attempt_at timestamptz NOT NULL`, `attempt_count INTEGER NOT NULL DEFAULT
0 CHECK >= 0`, `last_error_code VARCHAR(64) NULL`, `version BIGINT NOT NULL
DEFAULT 1 CHECK > 0`. `version` увеличивается при изменении задачи. `lease_owner` и `lease_until`
одновременно NULL или заполнены; `BLOCKED` и `COMPLETED` не выбираются
автоматически. Индекс `(next_attempt_at,id)` ограничен фазами `READING_WINNERS`,
`PREPARING`, `COLLECTING`, `DISTRIBUTING`. Проверка срока аренды выполняется при
claim. Согласованные FK и CHECK следует зафиксировать в будущей миграции.
Проверка сумм и принадлежности набора не заменяется ограничениями SQL.

Для `BLOCKED` `blocked_from_phase` обязательно содержит одну из рабочих фаз;
для остальных фаз — NULL. `random_seed` при наличии имеет ровно 32 байта и
не изменяется; `winners_loaded <= winner_count`. `COMPLETED` и `BLOCKED` не
имеют действующей аренды: переход атомарно очищает owner и deadline. Эти
ограничения должны быть отражены CHECK и контролем переходов в реализации.
Соответствие `challenge_id`, `result_id`, `set_version` и digest закрытому
набору проверяется транзакционно; межсервисный FK невозможен. Каждое движение
hold должно ссылаться на hold из этого challenge с совпадающей суммой, а каждое
allocation — на победителя этого расчёта; эти межстрочные правила требуют
локальных FK/уникальности и дополнительной транзакционной проверки.

### `coin_settlement_recovery_actions`

`id UUID PRIMARY KEY`, `settlement_id UUID NOT NULL FK → coin_settlements.id
RESTRICT`, `actor_user_id UUID NOT NULL`, `action VARCHAR(16) NOT NULL CHECK IN
('RESUME')`, `reason VARCHAR(500) NOT NULL`, `previous_phase VARCHAR(24) NOT
NULL`, `previous_error_code VARCHAR(64) NOT NULL`, `operation_id UUID NOT NULL
UNIQUE`, `request_digest BYTEA NOT NULL CHECK octet_length(request_digest)=32`,
`result JSONB NOT NULL`, `created_at timestamptz NOT NULL`; индекс
`(settlement_id,created_at,id)`. Неизменяемый аудит и результат повтора одного
уполномоченного действия сохраняются вместе с изменением settlement одной
транзакцией. Записи не содержат токенов или секретов; право чтения ограничено.

