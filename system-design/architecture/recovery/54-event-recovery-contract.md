# Восстановление событий и Coins: проект технического контракта

Проект для будущих Java / Spring-сервисов. Это не запущенный API, не миграция
и не проверенный runbook. Предыдущая стратегия: [очистка и восстановление](53-event-recovery-and-safe-cleanup.md).
Модели таблиц: [Challenge](../../services/challenge/38-challenge-data-model.md),
[Video Proof](../../services/video-proof/40-video-proof-service.md), [Moderation](../../services/moderation/45-moderation-data-model.md),
[Coins](../../services/coins/36-coins-data-model.md). У каждого своя БД; общих FK и commit нет.

## Границы и полномочия

Предлагается компонент эксплуатации `Recovery Coordinator`: он запускает и
отслеживает операции, но не пишет в бизнес-таблицы сервисов и не назначает
балансы. Компонент может поставляться отдельно от бизнес-сервисов; создание
нового публичного микросервиса или контейнера этой записью не предписывается.
У него своя durable БД управления, не общая БД Challenge или Coins.

Управляющие операции — только во внутренней сети, через gRPC с service identity,
проверкой аудитории и минимальных полномочий; запуск требует подтверждённой
идентичности оператора и роли `recovery.manage`, запросы и изменения аудируются.
Обычный пользователь и модератор не получают эти права. Пароли, токены, видео,
подписанные URL и полные payload в обычный лог не включаются. Конкретная схема
выдачи service credentials и управление секретами — отдельная спецификация.

## Таблицы управляющего компонента — единственное определение

### `recovery_runs`

`id UUID PRIMARY KEY`, `scope JSONB NOT NULL` с версиями схемы и перечнем
сервисов/потоков, `state VARCHAR(24) NOT NULL` (`PREPARING`, `QUIESCING`,
`SNAPSHOTTING`, `RECONCILING`, `VERIFYING`, `READY_TO_RESUME`, `RESUMING`,
`COMPLETED`, `BLOCKED`), `operator_id UUID NOT NULL`, `lease_generation BIGINT
NOT NULL DEFAULT 0 CHECK >= 0`, `lease_until timestamptz NULL`, `reason_code
VARCHAR(64) NOT NULL`, `created_at`, `updated_at timestamptz NOT NULL`.
В v1 — одна глобальная активная операция через частичный уникальный индекс на
константу для всех состояний кроме `COMPLETED`; `BLOCKED` остаётся активной до
управляемого разрешения, а не позволяет запустить конкурирующее восстановление.
Восстановление после падения координатора использует аренду и увеличенное
поколение fencing; старый исполнитель не может подтверждать новые этапы.

### `recovery_streams`

`run_id UUID NOT NULL FK → recovery_runs.id`, `producer VARCHAR(64) NOT NULL`,
`consumer VARCHAR(64) NOT NULL`, `stream VARCHAR(128) NOT NULL`, `epoch UUID
NOT NULL`, `journal_upper_bound BIGINT NULL`, `page_cursor TEXT NULL`,
`status VARCHAR(16) NOT NULL` (`WAITING`, `RUNNING`, `VERIFIED`, `BLOCKED`),
`last_error_code VARCHAR(64) NULL`, `updated_at timestamptz NOT NULL`, PK
`(run_id,producer,consumer,stream)`. Это прогресс, не доказательство полноты;
корректность зависит от подтверждённой остановки и сверки получателя.

### `recovery_checkpoints`

`run_id UUID NOT NULL FK → recovery_runs.id`, `topic VARCHAR(249) NOT NULL`,
`partition_id INTEGER NOT NULL CHECK >= 0`, `consumer_group VARCHAR(255) NOT
NULL`, `start_offset BIGINT NOT NULL CHECK >= 0`, `end_offset_exclusive BIGINT
NOT NULL CHECK >= 0`, `status VARCHAR(16) NOT NULL` (`CAPTURED`, `VERIFIED`,
`COMMITTED`), `captured_at timestamptz NOT NULL`, PK `(run_id, topic,
partition_id, consumer_group)`. Запись контрольной позиции не означает её
применения в Kafka. Audit операций управления — отдельная неизменяемая таблица
`recovery_audit(id UUID PK, run_id UUID NOT NULL FK, actor_id UUID NOT NULL,
action VARCHAR(96) NOT NULL, details JSONB NOT NULL, occurred_at timestamptz
NOT NULL)`; секреты и payload исключаются.

## Долговечная остановка и fencing

У каждого затронутого сервиса свой режим `NORMAL` / `QUIESCING` / `RECOVERY`,
локально и надёжно сохранённый с `run_id` и `epoch`. При `QUIESCING` сервис
отказывает новым внешним бизнес-командам и блокирует запуск фоновых заданий и
publisher; уже начатые транзакции доводятся до commit/rollback. Сервис
подтверждает `RECOVERY` только после остановки всех своих экземпляров, в том
числе worker и активных транзакций. Старый экземпляр, работающий с устаревшим
epoch, не может подтвердить новую запись; конкретный enforcement на уровне
БД/прав доступа должен быть реализован и проверен, один флаг в памяти не
является fencing. Если остановку всех writers невозможно доказать — `BLOCKED`.

Во время сверки разрешены только операции recovery, прошедшие проверку run и
epoch; любые вытекающие бизнес-факты сохраняются обычной локальной транзакцией
в журнал и outbox, но публикация outbox остаётся остановленной. Поэтому журнал
получателя может увеличиваться от самой сверки: границы выдачи фиксируются по
раундам после завершения соответствующих recovery-транзакций. Координатор
повторяет раунды, пока нет новых фактов, нерешённых зависимостей и расхождений.
Не гарантируется, что произвольный набор сервисов достигнет фиксированной точки:
при конфликте или неограниченном появлении фактов — `BLOCKED` и разбор.

## Служебные gRPC методы — проект v1

Все запросы содержат `run_id`, `epoch`, `request_id`; все UUID, а `epoch`
сверяется с локальным durable режимом сервиса. Каждый mutating метод имеет
стабильный `operation_id`: тот же payload возвращает результат, иные данные —
`ALREADY_EXISTS OPERATION_ID_CONFLICT`. `DEADLINE_EXCEEDED` / `UNAVAILABLE`
означают неизвестный исход: опрос или повтор с тем же ID, не новая операция.

| Вызов | Отправитель → получатель | Запрос и успешный ответ | Ошибки и смысл |
| --- | --- | --- | --- |
| `EnterRecovery` | Coordinator → каждый сервис | `{operation_id,scope}` → `{state,epoch}`; подтверждение `RECOVERY` только после остановки writers | `FAILED_PRECONDITION WRITERS_ACTIVE`, `PERMISSION_DENIED`; при недоступности повторить/опросить |
| `GetRecoveryState` | Coordinator → сервис | `{}` → `{state,epoch,writers_active}`; счётчик диагностический, не единственное доказательство fencing | `NOT_FOUND`, `UNAVAILABLE` |
| `OpenJournalSnapshot` | Coordinator → издатель | `{operation_id,stream}` → `{snapshot_id,upper_seq,event_count}` после полной остановки соответствующих writers | `FAILED_PRECONDITION WRITERS_ACTIVE`; верхняя граница не берётся до остановки |
| `ListJournalPage` | Coordinator → издатель | `{snapshot_id,after_seq,limit}` → `{events,next_after_seq}`; `limit` 1–500, строки включают неизменные event_id, тип, версию, payload и digest | `INVALID_ARGUMENT`, `NOT_FOUND SNAPSHOT_NOT_FOUND`, `FAILED_PRECONDITION EPOCH_CHANGED` |
| `ApplyRecoveryEvent` | Coordinator → получатель | `{operation_id,producer,stream,event}` → `{status: APPLIED | ALREADY_APPLIED | WAITING, event_id}` | `ALREADY_EXISTS PAYLOAD_CONFLICT`; конфликт не перезаписывается; `WAITING` требует durable pending |
| `VerifyRecoveryStream` | Coordinator → получатель | `{snapshot_id,producer,stream,upper_seq,event_count}` → `{status: VERIFIED | WAITING | CONFLICT, missing_count}` | `UNAVAILABLE`, `FAILED_PRECONDITION`; без доказуемой полноты не возвращать VERIFIED |
| `ResumeNormal` | Coordinator → каждый сервис | `{operation_id,verified_checkpoint_set}` → `{state: NORMAL}` только после успешной сверки и контрольных позиций | `FAILED_PRECONDITION RECOVERY_NOT_VERIFIED` |

Методы — проект имён и полей, не существующий proto. Журнал издателя выдаёт
полные проверенные события только уполномоченному координатору/получателю;
перенос через координатор не означает сохранение payload в его audit. Cursor
привязывается к snapshot и epoch; повтор страницы возвращает те же event ID.
`upper_seq` выбирается после блокировки всех относящихся writers и завершения
транзакций; не полагаться на sequence как commit order при активных writers.

Уточнение API snapshot: `OpenJournalSnapshot` и `ListJournalPage` в таблице
выше обозначают шаги, а конкретные методы v1 — `OpenRecoverySnapshot` с
`kind=EVENT_JOURNAL`, `GetRecoverySnapshot` и `ListRecoverySnapshotItems` из
[единого контракта снимков](55-recovery-snapshots-and-coins.md). Отдельные
дублирующие RPC `OpenJournalSnapshot` / `ListJournalPage` не вводятся.

`VerifyRecoveryStream` не может доказать полноту по одному числу записей:
координатор передаёт получателю или сверяет для каждой страницы список
`(event_id,digest)`; итог `VERIFIED` допустим только когда каждый факт либо
применён с тем же digest, либо явно заблокирован как ожидающий в отчёте и
зависимые функции не допускаются к запуску. Граница потока и перечень событий
фиксируются immutable snapshot манифестом с digest и количеством; проект схемы,
каноникализации и таблиц — [снимки и сверка Coins](55-recovery-snapshots-and-coins.md).
DDL и контрольные векторы ещё требуют реализации и проверки.
`WAITING` — не полный успех, пока он не разрешён либо область не выведена из
возобновляемого scope отдельным зафиксированным решением оператора.

## Kafka checkpoints и запуск после сверки

1. Publisher-ы остановлены и все производители рассматриваемых топиков
   подтверждены остановленными. Координатор фиксирует у брокера конкретный
   `end_offset_exclusive` каждой партиции каждого топика и group; существующие
   записи до этого offset покрываются сверкой журналов издателей. Если есть
   неизвестный издатель, неизвестный факт или данные брокера неполны — BLOCKED.
2. Сверяются все соответствующие журналы и получатели до фиксированной точки;
   каждое сообщение, которое попадёт в пропускаемый диапазон offset, должно быть
   сопоставлено с журналом или диагностировано. Восстановление одного топика
   не доказывает полноту событий, которые по ошибке никогда не попали в журнал.
3. При остановленных consumers оператор применяет сохранённые offsets через
   авторизованный механизм управления и проверяет прочитанное значение. Offset
   равен `end_offset_exclusive`, а не последнему существующему offset. Операция
   идемпотентна и фиксируется в audit; частичный сбой оставляет контур закрытым.
4. После повторной сверки контрольных точек включают consumers, затем publisher
   и новые бизнес-writers; публикации старых outbox могут прийти повторно и
   проходят inbox и бизнес-дедупликацию. Отклонение эпохи или обнаружение
   неконтролируемого writer блокирует процедуру.

При потере истории Kafka нельзя доказать соответствие **недоступного диапазона**
по самим отсутствующим сообщениям. В этом случае основание — проверенная
полнота журналов всех издателей за затронутый интервал и состояние БД; если её
нельзя доказать — не двигать checkpoint и не заявлять успешное восстановление.
Восстановление полной базы на раннюю точку создаёт отдельный риск пропавших
фактов и требует сверки с независимыми сторонами, описанной ниже.

## Coins: что сравниваем и что не делаем

- Challenge и Coins по `challenge_id`, `result_id`, `set_version` и digest
  сверяют закрытый набор hold, итог, число и digest победителей и состояние
  settlement. По стабильным ID сверяются reserve/release и строки ledger;
  статус частичного расчёта остаётся частичным до окончания всех выплат.
- Повтор `challenge.finished` применяет штатный обработчик Coins: inbox и
  уникальные ограничения `result_id`, `challenge_id`, hold/операций защищают
  от повторного проведения. Компенсация создаёт отдельную ledger-запись;
  координатор не устанавливает баланс SQL-командой и не редактирует ledger.
- Проверяем инварианты применительно к модели: состояние holds и кошельков,
  суммы ledger и escrow, неотрицательность и сохранение полного банка с учётом
  зафиксированных фаз. Пока есть расхождение, settlement и возобновление
  затронутого контура BLOCKED; причина и ID операций попадают в audit.
- Если подтверждённый commit Coins отсутствует после восстановления A и его
  нельзя доказать из непрерывного WAL, синхронной копии и других сохранённых
  свидетельств, **нельзя гарантировать RPO 0** и нельзя автоматически создать
  заново выплату. Остановить денежные действия и передать на разбор. B не
  повышается до основной — сохраняется выбранное пользователем правило.

Проект API снимков, сверки hold/ledger и фаз settlement:
[снимки и сверка Coins](55-recovery-snapshots-and-coins.md). Полный порядок
блокировок и проверки каждой фазы ещё требуют реализации и тестирования.

## Backup/WAL и восстановление PostgreSQL

Рабочее направление: для каждой БД отдельные проверяемые base backup и
непрерывный архив WAL, изолированные от основной БД, с хранением конфигурации
в отдельном управляемом источнике. PostgreSQL PITR требует доступного base
backup и непрерывной цепочки WAL как минимум с его начала; реплика не является
независимой защитой от ошибочного удаления. [Документация PostgreSQL 17](https://www.postgresql.org/docs/17/continuous-archiving.html).

При отказе: закрыть затронутый контур, проверить backup/WAL и точку восстановления,
восстановить **в отдельную изолированную среду**, проверить миграционную версию,
целостность журнала, бизнес-инварианты и доступность нужной истории. Сравнить
состояния сервисов и только после сверки допускать его к работе. Для Coins A
остаётся основной; B не автоматически повышается. Размещение всех копий на
одной машине не защищает от потери этой машины.

Выбран проект инструмента и стартового расписания:
[план backup](56-postgresql-backup-plan.md). Это не готовая инструкция с
командами: внешний repository и механизм ключей, RPO/RTO отдельных сервисов, восстановление
после полной потери A и безопасная замена экземпляра ещё требуют проектирования.
Без необходимых WAL или непроверенной копии гарантировать восстановление нельзя.

## Проверки до готовности

Интеграционные проверки: writer после смены epoch; потерянный ответ каждой
команды; повтор страниц и изменённый digest; появление производных событий при
replay и фиксированная точка; crash на каждом этапе и частично применённые
checkpoints; consumer offset после удаления Kafka-диапазона; restored DB до
сохранённого результата; потеря WAL, split brain и отказ синхронной реплики;
ошибочный повтор settlement и компенсирующих операций. Нужны отдельные реальные
PostgreSQL, Kafka и worker, проверенный backup и восстановление. Проверки не
запускались; реализация, API и настройки ещё отсутствуют.
