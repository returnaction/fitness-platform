# Notifications — модель данных и контракты v1

Проект будущего Java / Spring-сервиса, не реализованная функциональность.
Единые определения таблиц Notifications — в этом документе. Сервис хранит
уведомления, настройки и попытки доставки; Challenge, Moderation и Coins
остаются источниками бизнес-статусов и балансов. Push не означает, что человек
получил или прочитал сообщение.

## Назначение и источники

Сервис получает факты через Kafka, создаёт историю внутри приложения и при
разрешённых настройках пытается доставить push. Правила и задачи дня не
вычисляет самостоятельно. События о начале/переносе/отмене challenge и его
окончательном результате принадлежат Challenge, исходные вердикты и решения
апелляций — Moderation, подтверждённые возвраты/начисления — Coins. Для одного типа
уведомления назначается один источник; например, событие отмены не доказывает,
что Coins уже вернул ставку. Доставка сообщения о возврате — только по факту
Coins.

Для пользовательского уведомления о решении проверки и апелляции выбран
Challenge: он сначала применяет решение Moderation к заданию, затем публикует
собственное уведомление с ID получателя через outbox. Moderation не отправляет
второе такое уведомление. Это не раскрывает ID участника модератору и не
заставляет Notifications угадывать владельца по событию Moderation.

Настройки могут отключить push по категории; история и состояния в приложении
остаются. Принятие видео и собственная подача апелляции — статус в приложении,
отдельный push для них не создаём. Перед отправкой напоминания сервис запрашивает
актуальность задания у Challenge; из-за гонки между проверкой и отправкой
абсолютная актуальность не гарантируется. Асинхронная реплика БД Notifications
может отставать и последние подтверждённые уведомления/настройки могут быть
потеряны после отказа основной базы; восстановление сверяется по событиям и
не гарантирует восстановления данных вне доступного журнала.

## Единые таблицы PostgreSQL

UUID и `timestamptz` UTC; таблицы находятся в БД Notifications. Внешний
`user_id` — ID Identity без межсервисного FK. Токены устройств, payload и
история доступны только авторизованным компонентам; токены не пишутся в логи.

### `notification_preferences`

`user_id UUID NOT NULL`, `category VARCHAR(32) NOT NULL` (`CHALLENGE`,
`REVIEW`, `RESULT`, `COINS`, `REMINDER`), `push_enabled BOOLEAN NOT NULL`,
`version BIGINT NOT NULL CHECK > 0`, `created_at`, `updated_at timestamptz NOT
NULL`, PK `(user_id,category)`. Если строки ещё нет, push включён по умолчанию
при наличии разрешения устройства; создание/изменение версии сериализуется по
пользователю и категории. Значения по умолчанию — техническое предложение v1;
перед реальным запуском проверить UX и требования платформы.

### `notification_devices`

`id UUID PRIMARY KEY`, `user_id UUID NOT NULL`, `installation_id UUID NOT NULL`,
`platform VARCHAR(16) NOT NULL` (`ANDROID`, `IOS`), `token_ciphertext BYTEA NOT
NULL`, `token_digest BYTEA NOT NULL` длиной 32 байта, `key_version VARCHAR(32)
NOT NULL`, `state VARCHAR(16) NOT NULL` (`ACTIVE`, `INVALID`, `REVOKED`),
`created_at`, `updated_at timestamptz NOT NULL`, UNIQUE `(user_id,installation_id)`;
частичный UNIQUE `token_digest WHERE state='ACTIVE'`. Токен защищён шифрованием
с ключом вне БД; digest — HMAC с отдельным ключом, не секрет в коде. Ротацию
ключей нужно реализовать с поддержкой нескольких версий. Поскольку HMAC одного
токена разными ключами различается, UNIQUE digest не обеспечивает уникальность
при ротации ключа: до включения ротации нужен стабильный индексный ключ с
отдельной политикой ротации либо атомарная миграция и проверка всех активных
токенов; текущая схема не считается готовой к ротации без этого механизма.
При зарегистрированном в Notifications выходе пользователя привязка устройства
этого пользователя отзывается. При подтверждённом переключении Notifications
должен в своей локальной транзакции отключить старую привязку и включить новую;
это не общая транзакция Identity и Notifications. Доставка команды выхода при
сбое, доказательство владения installation и разрешение конкурентных смен
аккаунта ещё не определены, поэтому нельзя обещать мгновенное отключение
старого получателя при каждом выходе. Гонка уже отправленного push и отзыва
также не исключается — текст push не
должен раскрывать чувствительную информацию. Доставка через провайдера,
сроки повторов и ключи настраиваются отдельно.

### `notifications`

`id UUID PRIMARY KEY`, `user_id UUID NOT NULL`, `source_service VARCHAR(64) NOT
NULL`, `source_event_id UUID NOT NULL`, `notification_type VARCHAR(48) NOT
NULL`, `business_key VARCHAR(192) NOT NULL`, `category VARCHAR(32) NOT NULL`,
`title VARCHAR(160) NOT NULL`, `body VARCHAR(1000) NOT NULL`, `data JSONB NOT
NULL`, `created_at timestamptz NOT NULL`, `read_at timestamptz NULL`, UNIQUE
`(user_id,notification_type,business_key)`; индекс `(user_id,created_at DESC,id
DESC)`. `business_key` назначает сервер на основании бизнес-события, не клиент;
payload не содержит видео, токены или временные ссылки. Прочитанное состояние
не изменяет факт доставки. Согласованный retention истории пользователя пока
не выбран; автоматического удаления без политики нет.

### `notification_deliveries`

`id UUID PRIMARY KEY`, `notification_id UUID NOT NULL FK → notifications.id`,
`device_id UUID NOT NULL FK → notification_devices.id`, `state VARCHAR(16) NOT
NULL` (`PENDING`, `SENDING`, `RETRY_WAIT`, `SENT`, `FAILED`, `SUPPRESSED`),
`attempt_count INTEGER NOT NULL CHECK >= 0`, `next_attempt_at`, `lease_until
timestamptz NULL`, `lease_generation BIGINT NOT NULL CHECK >= 0`,
`provider_message_id VARCHAR(255) NULL`, `last_error_code VARCHAR(64) NULL`,
`created_at`, `updated_at timestamptz NOT NULL`, UNIQUE
`(notification_id,device_id)`, индекс `(state,next_attempt_at)`.
`SENT` означает только принятие провайдером; результат после потери ответа
провайдера неизвестен, повтор может создать дубль push. Серверная идемпотентность
истории не гарантирует exactly-once на телефоне. Временные ошибки повторяются с
задержкой; постоянно неверный токен переводит устройство в `INVALID` по
подтверждённому ответу провайдера. Число попыток и точные коды провайдера должны
быть заданы при выборе адаптера, не делаем вид, что он уже выбран.

### `notification_fanout_jobs`, `notification_fanout_recipients`

`notification_fanout_jobs`: `id UUID PK`, `source_event_id UUID NOT NULL UNIQUE`,
`notification_type VARCHAR(48) NOT NULL`, `audience_snapshot_id UUID NOT NULL`,
`state VARCHAR(16) NOT NULL` (`PENDING`, `RUNNING`, `RETRY_WAIT`, `COMPLETED`,
`BLOCKED`), `next_cursor TEXT NULL`, `lease_until timestamptz NULL`,
`lease_generation BIGINT NOT NULL DEFAULT 0 CHECK >= 0`, `created_at`,
`updated_at timestamptz NOT NULL`. `audience_snapshot_id` — внешний ID снимка
Challenge, не FK. Обработчик сохраняет страницу и новый cursor одной транзакцией
под действующей арендой; только актуальное поколение подтверждает прогресс.

`notification_fanout_recipients`: `job_id UUID NOT NULL FK →
notification_fanout_jobs.id`, `user_id UUID NOT NULL`, `notification_id UUID NOT
NULL FK → notifications.id`, `created_at timestamptz NOT NULL`, PK
`(job_id,user_id)`, UNIQUE `(notification_id)`. Повтор страницы не создаёт
второе уведомление; несовпадение business key — разбор. Job завершается только
после сохранения последней страницы и сверки ожидаемого числа получателей снимка.

### `notifications_inbox`, `notifications_pending_events`, `notifications_outbox`

`notifications_inbox`: `consumer VARCHAR(96) NOT NULL`, `event_id UUID NOT NULL`,
`payload_digest BYTEA NOT NULL` длиной 32, `processed_at timestamptz NOT NULL`,
PK `(consumer,event_id)`. Создание истории, inbox и доставки выполняется одной
локальной транзакцией; при отключённом push история сохраняется без доставки.

`notifications_pending_events`: `consumer VARCHAR(96) NOT NULL`, `event_id UUID
NOT NULL`, `event_type VARCHAR(128) NOT NULL`, `schema_version INTEGER NOT NULL
CHECK > 0`, `payload JSONB NOT NULL`, `payload_digest BYTEA NOT NULL` длиной 32,
`dependency_type VARCHAR(64) NOT NULL`, `dependency_id UUID NOT NULL`, `state
VARCHAR(16) NOT NULL` (`WAITING`, `PROCESSING`, `APPLIED`, `QUARANTINED`),
`attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`, `next_attempt_at`,
`lease_until timestamptz NULL`, `lease_generation BIGINT NOT NULL DEFAULT 0
CHECK >= 0`, `last_error_code VARCHAR(64) NULL`, `created_at`, `updated_at
timestamptz NOT NULL`, PK `(consumer,event_id)`; индексы `(state,next_attempt_at)`
и `(dependency_type,dependency_id,state)`. Если нужной зависимости нет, событие
устойчиво сохраняется до подтверждения Kafka offset; после применения inbox,
изменение истории и `APPLIED` — один commit. Невыполненное ожидание не удаляем
по короткому TTL; конфликт digest отправляется на разбор.

`notifications_outbox`: `event_id UUID PK`, `event_type VARCHAR(128) NOT NULL`,
`schema_version INTEGER NOT NULL CHECK > 0`, `aggregate_id UUID NOT NULL`,
`payload JSONB NOT NULL`, `created_at timestamptz NOT NULL`, `published_at
timestamptz NULL`. В v1 исходящие бизнес-события Notifications не обязательны;
если они добавлены, outbox используется в той же локальной транзакции.

## REST API для приложения

Все пути требуют действующую сессию. `user_id` берётся из проверенного токена;
чужую историю и токены устройства читать нельзя. Ошибка:
`{"error":{"code":"validation_error","message":"Проверьте данные","request_id":"<uuid>"}}`.
Общие ответы: `400 invalid_request`, `401 unauthenticated`, `403 forbidden`,
`404 not_found` для чужого или отсутствующего объекта, `422 validation_error`,
`429 rate_limited`, `503 notifications_unavailable`, `500 internal_error`.

| Метод | Запрос | Ответ |
| --- | --- | --- |
| `GET /api/v1/notifications?limit=20&cursor=...` | limit 1–100, порядок `(created_at DESC,id DESC)`. | `200 {"items":[{"id":"...","category":"REVIEW","title":"Видео проверено","body":"Откройте challenge","data":{"challenge_id":"..."},"created_at":"...","read_at":null}],"next_cursor":null}` |
| `GET /api/v1/notifications/preferences` | — | `200 {"items":[{"category":"CHALLENGE","push_enabled":true,"version":1}]}`; возвращаются все поддерживаемые категории, включая значения по умолчанию. |
| `PATCH /api/v1/notifications/preferences/{category}` | `{"push_enabled":false,"version":1}` | `200 {"category":"CHALLENGE","push_enabled":false,"version":2}`; устаревшая версия — `409 version_conflict`. |
| `POST /api/v1/notifications/{id}/read` | `{}` | `200 {"id":"...","read_at":"2026-11-05T12:00:00Z"}`; повтор не меняет первоначальный read_at. |
| `PUT /api/v1/notification-devices/{installation_id}` | `{"platform":"ANDROID","push_token":"<token>"}` | `200 {"installation_id":"...","state":"ACTIVE"}`; токен в ответ не возвращается. |
| `DELETE /api/v1/notification-devices/{installation_id}` | — | `204`; повтор владельца безопасен. |

PATCH и PUT требуют проверки версии/владельца, read — естественно идемпотентен.
Потеря ответа возвращается повтором тех же параметров; изменение параметров —
новое действие. Конфликт одного активного токена разных владельцев должен
сериализоваться вместе с безопасным отвязыванием предыдущей установки; политика
проверки владения установкой через Identity ещё требует отдельного контракта.
Смена категории PATCH и прочтение выполняются на primary, история может читаться
с реплики только если клиент допускает отставание; после собственной операции
читать с primary. Cursor не является авторизацией; формат и подпись курсора
согласуются с общей пагинацией.

Для отсутствующей строки предпочтения GET отдаёт синтетическое значение
`push_enabled=true, version=1`. Первый PATCH с `version=1` делает локальный
`INSERT` версии 2; при конкурирующем первом PATCH уникальный PK выбирает одного
победителя, второй после сверки получает `409 version_conflict`. Созданная
строка становится единственным источником значения категории. Это не отменяет
системный запрет уведомлений на устройстве и не означает доставку push.

## Входящие события и доставка

### Что уже выбрано и что ещё не закрыто

- История, пользовательские настройки и попытки push принадлежат Notifications;
  бизнес-решение остаётся у Challenge, а денежная проводка — у Coins.
- Для `REVIEW_DECIDED` и `APPEAL_DECIDED` источник уведомления — Challenge
  после применения решения; для `COINS_CREDITED` и `STAKE_RETURNED` — Coins
  после подтверждённой проводки. Оболочка ниже — проект, а не готовые к
  подключению события каждого владельца.
- У групповых уведомлений проект предусматривает фиксированный снимок
  аудитории Challenge и постраничную доставку. Имена топиков, полные схемы
  опубликованных владельцами сообщений и совместимость ещё не сверены.
- REST-привязка устройства и локальное отключение предыдущего пользователя
  описаны выше, но нет согласованного надёжного контракта получения отзыва
  сессии из Identity, проверки владения установкой, одновременных регистраций
  и обработки уже отправленного push. Один `installation_id` из запроса
  сам по себе не доказывает право отвязать устройство другого пользователя.
- Выбор push-провайдера, сроки и коды повторов, срок хранения истории и
  безопасная ротация ключа digest устройства пока открыты. Правила отдельной
  категории и требования платформы проверяются до выпуска.

Рабочий mapping: Challenge публикует `challenge.started`, `challenge.postponed`,
`challenge.cancelled`, `challenge.finished`; исходные решения Moderation
сначала применяет Challenge; Coins публикует подтверждённые события
начисления/возврата. Фактические имена и DTO должны совпадать с контрактами
владельцев; одна таблица mappings не делает их существующими. В частности,
событие модерации не содержит пользовательский ID — Notifications напрямую
его для пользовательского сообщения не использует. После применения Challenge
публикует отдельное событие с ID получателя по контракту ниже.

Проект envelope `notification.requested` v1: `event_id`, `event_type`,
`schema_version`, `occurred_at`, `producer`, `correlation_id`,
`data:{notification_type,business_key,recipient_user_id,challenge_id,reference_id}`.
`recipient_user_id` обязателен для одного получателя; `reference_id` — ID
применённого решения/результата или операции Coins, не секрет. Типы v1:
`REVIEW_DECIDED`, `APPEAL_DECIDED`, `COINS_CREDITED`, `STAKE_RETURNED`;
один факт и один получатель имеют стабильный business_key. Challenge публикует
первые два после применения, Coins — последние два после фиксации ledger. Тело
push использует общий текст без причины и суммы; подробности в авторизованной
истории. Окончательные исходные DTO должны быть согласованы с владельцем.

Для группового challenge Challenge фиксирует неизменяемый снимок получателей,
передаёт `audience_snapshot_id`, `expected_count` и digest; Notifications читает
страницы по внутреннему авторизованному gRPC `ListAudience(snapshot_id,cursor,
limit)` (limit 1–500), при недоступности ожидает. Снимок и проект digest
определены в [модели Challenge](../challenge/38-challenge-data-model.md#аудитория-уведомления).
Сервис Challenge проверяет identity Notifications и SEALED, возвращает
`{snapshot_id,users:[uuid],expected_count,digest_hex,next_cursor}`. Неверный
cursor — `INVALID_ARGUMENT`, неготовый snapshot — `FAILED_PRECONDITION`,
недоступность — `UNAVAILABLE`. Notifications сверяет полный count/digest до
COMPLETED. В v1 применяется фильтр `ALL_JOINED_V1`, формат cursor и вычисление
digest заданы в модели Challenge. Контрольные векторы, восстановление worker и
проверки конкурирующих вступлений ещё необходимы: это проект, не гарантия.

Получатель фиксирует уведомление и inbox, затем worker отправляет push через
адаптер с ограниченным timeout, арендует delivery и подтверждает результат.
Потеря ответа провайдера может привести к повтору, поэтому клиент по ID
уведомления по возможности сворачивает дубли, но полную гарантию это не даёт.
Прежде чем отправить reminder, worker обращается к Challenge за актуальным
правом и задачей: при недоступности откладывает, не отправляет по устаревшему
предположению. Между проверкой и push возможна гонка; в самом сообщении нет
ложного окончательного утверждения о состоянии задания.

## Проверки перед разработкой

Реальный PostgreSQL: повтор Kafka и новый event ID с тем же business key,
конкурентные read/PATCH, транзакция inbox и истории, смена владельца token,
падение delivery worker и stale lease. Интеграционно: отключённые push и
доступная история, отозванное устройство, недоступный Challenge при reminder,
потеря ответа push provider и повтор, фан-аут после перезапуска, восстановление
после отказа асинхронной реплики и устаревшая история. Тесты не запускались.
