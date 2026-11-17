# Spec 020: challenge-service MVP — дуэльные челленджи со ставкой монет

> **Статус:** implemented — MVP реализован и проверен 2026-11-17; открытые вопросы для будущих спецификаций сохранены в [questions.md](questions.md).
> **Номер:** 020
> **Ветка:** `feature/50-challenge-service-dev` (создана пользователем; новая ветка не требуется)
> **Дата:** 2026-11-17
> **Документы:** [план](plan.md), [задачи](tasks.md), [решения](decisions.md), [вопросы](questions.md), [проверка](verification.md).
> **Основание:** [вступление](../../system-design/services/challenge/20-join-service-contract-draft.md), [отмена](../../system-design/services/challenge/21-challenge-cancellation-draft.md), [модель данных Challenge](../../system-design/services/challenge/38-challenge-data-model.md), [HTTP API Challenge](../../system-design/services/challenge/39-challenge-api.md), [расписание и доступ](../../system-design/services/challenge/60-challenge-schedule-and-access.md), [контракт ставок](../../system-design/services/coins/34-coins-stake-and-settlement-contract.md), [расчёт банка](../../system-design/services/coins/35-coins-large-settlement.md), [модель данных Coins](../../system-design/services/coins/36-coins-data-model.md), [топология Kafka](../../system-design/architecture/messaging/19-kafka-topology.md).

## Цель

Реализовать минимальный цикл «создать черновик → опубликовать → второй участник вступил → старт по заполнению → фиксация результата → расчёт в Coins» в новом `challenge-service` при поддержке `coins-service` и `gateway-service`.

MVP нужен как проверяемое техническое ядро дуэльного челленджа: он фиксирует владение данными, денежные резервы, идемпотентность, восстановление операций и события, не добавляя расписание дней, видео и модерацию.

## Подтверждённое состояние на 2026-11-17

| Компонент | Состояние |
| --- | --- |
| `challenge-service` | Существует каркас: Actuator, `RequestIdFilter`, `SecurityConfiguration` с `denyAll`, Dockerfile, `.env.example`; БД, JPA, API и gRPC отсутствуют. |
| `coins-service` | Реализован только кошелёк и `GET /api/v1/coins/me` Spec 017; таблица `coin_wallets`; резервов, holds, ledger ставок, settlement и Kafka нет. |
| `gateway-service` | Существуют маршруты Identity, Accounts и Coins; upstream `CHALLENGE`, маршрутов challenge и политики `SERVICE` нет. |
| Контракты | `challenge-v1.yaml` отсутствует; черновики `coins-stake-v1.proto` и `challenge-results-v1.proto` есть только в `system-design/contracts` и в сборку не подключены. |
| Kafka | В проекте и Compose отсутствует; топиков и зависимостей нет. |
| PostgreSQL | Локальный PostgreSQL установлен; база `fitness_challenge` и роль не созданы. |
| Спецификации | Номер 019 занят `019-mobile-home-balance`; новая спецификация получает номер 020. |

## Границы

### Входит в эту спецификацию

1. Только `HEAD_TO_HEAD` с `min = max = 2`, `PUBLIC`, старт `WHEN_FULL`. Режим, видимость и состав участников задаются сервером; клиент их не выбирает.
2. Состояния challenge: `DRAFT`, `OPEN`, `ACTIVE`, `FINISHED`, `CANCELLED`. `AWAITING_RESULTS` остаётся зарезервированным в enum, но в MVP не используется.
3. Состояния участия: `JOINED`, `ACTIVE`, `WINNER`, `COMPLETED`.
4. HTTP API владельца `contracts/openapi/challenge-v1.yaml`: создание черновика, PATCH черновика, publish, join, cancel, чтение challenge, чтение операции, чтение результата.
5. Внутренний gRPC `FinalizeResult` для доверенного источника итога и `ListResultWinners` для Coins; mTLS и проверка service identity.
6. gRPC Coins: `ReserveStake`, `ReleaseStake`, `GetStakeOperation`, `BeginStakeSet`, `PutStakeBatch`, `FinalizeStakeSet`, `FreezeStakeSet`, `CompleteStakeCancellation`.
7. События через outbox: `challenge.finished` (Challenge → Coins), `coins.settlement_completed` (Coins → Challenge).
8. Клиентский `Idempotency-Key` на изменяющих HTTP-методах; durable operation ID для gRPC; recovery worker незавершённых операций.
9. Gateway: upstream `CHALLENGE`, маршруты с политикой `AUTHENTICATED`, forwarding `Authorization` и `X-Request-Id`; в Gateway только `$ref` на контракт владельца.
10. Единый формат ошибок `{"error":{"code","message","requestId"}}` с необязательным `details`.
11. Тесты repository, web/security, идемпотентности, конкурентных сценариев, отказов Coins и восстановления; Testcontainers PostgreSQL и Kafka, gRPC mock.
12. Локальная Kafka в Docker Compose в режиме KRaft для сквозной проверки; PostgreSQL остаётся вне Docker.

13. Принят **вариант B — architecture-faithful MVP**. Поэтому batch seal, freeze/complete, `ListResultWinners`, Kafka/outbox/inbox и детальные settlement-таблицы остаются в MVP; упрощения Lean-варианта не применяются. Перенос части механизмов в отдельную спеку не выполняется.

### Не входит в эту спецификацию

- `GROUP`, `PRIVATE`, пароли, приглашения, ссылки доступа.
- `AT_SCHEDULE`, переносы дат, lifecycle-scheduler, `START_CHECK`, `DAY_DEADLINE`, `UPLOAD_GRACE`, `APPEAL_DEADLINE`.
- Дни, задания, упражнения, снимки каталога, `GET /days`, видео, модерация, апелляции.
- Уведомления, аудитории, nickname и интеграция с Accounts.
- Поиск, фильтры, курсорная пагинация, список участников отдельным endpoint.
- `MULTIPLE_WINNERS`, споры, ручные корректировки, большой банк с escrow.
- Универсальный settlement worker с пакетами и арендой; в MVP расчёт выполняется для двух holds одной транзакцией.
- Event journal, pending events, daily reward, challenge projection названий.
- Rate limiting, TTL и автоматическая очистка идемпотентности и операций.
- Docker-выкладка challenge-service, release scripts и серверное развёртывание.
- Публичное назначение победителя клиентом.

## Пользователи и права

| Роль | Что может делать |
| --- | --- |
| Создатель | Создать и изменить свой черновик, опубликовать его, отменить, пока нет других участников, читать challenge и результат. |
| Второй участник | Вступить в опубликованный `OPEN` challenge, читать challenge и результат, читать только свои операции. |
| Другой аутентифицированный пользователь | Читать опубликованный challenge и результат; чужой черновик для него — `404`. |
| Доверенный источник результата | Вызвать внутренний gRPC `FinalizeResult` с mTLS. Публичного HTTP-метода нет. |
| `coins-service` | Выполнять stake-операции по mTLS, читать список победителей, потреблять `challenge.finished`, публиковать `coins.settlement_completed`. |
| `gateway-service` | Единственный валидатор пользовательского JWT и сессии; передавать claims в `Authorization` и канонический `X-Request-Id`. |

## Сценарии

### Сценарий: создать черновик

**Дано:** пользователь аутентифицирован через Gateway.
**Когда:** он отправляет `POST /api/v1/challenges/drafts` с валидным `Idempotency-Key` и пустым телом.
**Тогда:** создаётся черновик `DRAFT`, версия 1; ответ `201 Created` с `Location`; монеты не резервируются, участие не создаётся.

### Сценарий: изменить черновик

**Дано:** черновик принадлежит пользователю и находится в `DRAFT`.
**Когда:** он отправляет `PATCH /api/v1/challenges/{challengeId}/draft` с `version` и изменяемыми полями.
**Тогда:** поля сохраняются атомарно, версия увеличивается; ответ `200 OK`. Повтор с тем же ключом возвращает сохранённый ответ.

### Сценарий: опубликовать

**Дано:** черновик заполнен, создатель имеет достаточно монет.
**Когда:** он отправляет `POST /api/v1/challenges/{challengeId}/publish` с `version`.
**Тогда:** создаётся durable-операция `PUBLISH`, Coins резервирует ставку, создатель становится участником `JOINED`, challenge переходит в `OPEN`; ответ `200 OK`. Если итог Coins неизвестен — `202 Accepted` со `statusUrl`.

### Сценарий: вступить

**Дано:** challenge в `OPEN`, есть свободное место, пользователь ещё не участвует.
**Когда:** он отправляет `POST /api/v1/challenges/{challengeId}/joins`.
**Тогда:** сначала создаётся резерв в Coins, затем под блокировкой строки challenge подтверждается место и `join_sequence`. Ответ `200 OK` или `202 Accepted`, если операция ещё выполняется.

### Сценарий: старт по заполнению

**Дано:** подтверждены оба участника, набор ставок ещё не закрыт.
**Когда:** Coins подтверждает `FinalizeStakeSet`.
**Тогда:** challenge переходит в `ACTIVE`, участники — в `ACTIVE`. До подтверждения seal challenge остаётся `OPEN` с `joined = 2`, новые join получают `409 challenge_full`.

### Сценарий: отменить

**Дано:** создатель — единственный подтверждённый участник, challenge в `OPEN`.
**Когда:** он отправляет `POST /api/v1/challenges/{challengeId}/cancel`.
**Тогда:** challenge становится `CANCELLED`, создаётся один release intent на hold, вызываются `FreezeStakeSet`, `ReleaseStake`, `CompleteStakeCancellation`. `refundStatus` равен `PENDING`, пока Coins не подтвердит возврат, затем `COMPLETED`.

### Сценарий: зафиксировать результат

**Дано:** challenge в `ACTIVE`, набор `SEALED`, победители известны доверенному источнику.
**Когда:** источник вызывает gRPC `FinalizeResult` с `resultId`, `outcome` и списком победителей.
**Тогда:** одной транзакцией сохраняются результат, победители, digest, статусы участников, `FINISHED` и outbox `challenge.finished`.

### Сценарий: расчёт Coins

**Дано:** Coins получил `challenge.finished` и подтвердил список победителей по digest.
**Когда:** расчёт завершён.
**Тогда:** `SINGLE_WINNER` получает весь банк, `DRAW` возвращает каждому его ставку; публикуется `coins.settlement_completed`. Challenge сохраняет проекцию `COMPLETED` только после этого события.

### Ошибки и граничные случаи

| Условие | Ожидаемое поведение для клиента |
| --- | --- |
| Некорректный JSON, неизвестное поле, отсутствующий `Idempotency-Key` | `400 invalid_request`; состояние не меняется. |
| Нарушены правила полей или состояния | `422 validation_error` с `details`. |
| Устаревшая `version` | `409 version_conflict`. |
| Черновик уже не `DRAFT` | `409 challenge_not_editable`. |
| Недостаточно монет | `409 insufficient_coins`; черновик остаётся `DRAFT`. |
| У пользователя уже есть активный hold | `409 stake_conflict`; новый резерв не создаётся. |
| Мест нет | `409 challenge_full` после подтверждённого возврата резерва; иначе `202`. |
| Повторное вступление | `409 already_joined`. |
| Challenge уже `ACTIVE` | `409 challenge_started`. |
| Challenge `CANCELLED` | `409 challenge_cancelled`. |
| Отмена при наличии другого участника | `409 cancellation_not_allowed`. |
| Результат уже зафиксирован с другим payload | `409 result_conflict`. |
| Чужой черновик или чужая операция | `404 challenge_not_found` / `404 operation_not_found`. |
| Итог Coins неизвестен | `202 Accepted` с `statusUrl`; не `409` и не `503`. |
| БД недоступна, операцию невозможно сохранить или прочитать | `503 service_unavailable` в едином формате. |
| Нет или невалиден access token | `401 unauthenticated`; через Gateway также `401`. |

## Бизнес-правила

### Ограничения MVP

Предлагаемые значения; подтверждаются на ревью спецификации до статуса `approved`:

- `title` — 1–120 символов, обязателен при публикации;
- `description` — до 2000 символов, необязателен;
- `stakeAmount` — целое от 1 до 1 000 000;
- `durationDays` — целое от 1 до 30;
- режим — `HEAD_TO_HEAD`, видимость — `PUBLIC`, `min = max = 2`, старт — `WHEN_FULL`;
- список дней и заданий в MVP отсутствует; challenge создаётся как технический контур без расписания.

Сервер не принимает сумму ставки из запроса клиента при publish/join: она берётся из сохранённого черновика.

### Состояния и переходы

Challenge:

- `DRAFT → OPEN` — publish после подтверждённого резерва создателя;
- `OPEN → ACTIVE` — только после подтверждённого `FinalizeStakeSet`;
- `ACTIVE → FINISHED` — фиксация окончательного результата;
- `OPEN → CANCELLED` — отмена создателем, пока нет других участников;
- переходы сериализуются блокировкой строки `challenges`;
- `FINISHED` и `CANCELLED` окончательны; `AWAITING_RESULTS` в MVP не используется.

Участники:

- создатель становится `JOINED` при успешном publish;
- второй участник становится `JOINED` после подтверждения места;
- при `ACTIVE` все участники становятся `ACTIVE`;
- при `SINGLE_WINNER` победитель — `WINNER`, проигравший — `COMPLETED`;
- при `DRAW` оба — `COMPLETED`;
- при отмене статус участия не подменяет отмену; отмена видна в статусе challenge и `refundStatus`.

### Порядок денежных операций

- Publish и join сначала сохраняют durable-операцию, затем вызывают Coins, затем повторно проверяют состояние под блокировкой challenge.
- Место не блокируется на время проверки баланса.
- За последнее место побеждает ровно одна операция.
- Если подтверждение места невозможно, создаётся один release intent на hold; окончательный отказ клиенту возвращается только после подтверждения возврата.
- Автоматическое освобождение hold по таймеру запрещено.
- Внешние gRPC-вызовы не выполняются внутри открытой транзакции PostgreSQL.

### Идемпотентность и восстановление

- `Idempotency-Key` обязателен на `POST`/`PATCH`, имеет формат UUID, привязан к пользователю, методу и пути.
- Тот же ключ с тем же отпечатком возвращает сохранённый HTTP-ответ; другой отпечаток — `409 idempotency_key_conflict`.
- Durable operation ID не меняется при повторах; совпадающий запрос возвращает сохранённый исход, другой payload — конфликт operation ID.
- Recovery worker продолжает незавершённые publish/join/release/seal-операции после рестарта.
- Очистка идемпотентности и незавершённых операций в MVP не выполняется.

### Результат и расчёт

- Публичного назначения победителя нет.
- `MULTIPLE_WINNERS` отклоняется.
- `FinalizeResult` идемпотентен по `resultId`: тот же payload возвращает сохранённый результат, другой — `409 result_conflict`.
- `winnersDigest` — SHA-256 от user UUID в lowercase с дефисами, отсортированных лексикографически, через `\n` без конечного перевода строки.
- `setDigest` считается по строкам `hold_id,user_id,amount`, отсортированным по `hold_id`, через `\n` без конечного перевода строки.
- Банк равен сумме holds. При `SINGLE_WINNER` победитель получает весь банк. При `DRAW` каждый получает свою ставку; остатка нет.
- Coins — единственный источник истины балансов, holds и ledger; Challenge не изменяет кошельки.
- Проекция `challenge_settlement_status = COMPLETED` появляется только после `coins.settlement_completed`.

### Блокировки

Порядок внутри базы Challenge:

`challenges` → `challenge_operations` → `challenge_stake_sets` → `challenge_release_intents` → `challenge_results` → outbox.

Порядок внутри базы Coins:

`coin_stake_sets` → `coin_stake_operations` → `coin_holds` по возрастанию UUID → `coin_wallets` по возрастанию user UUID → ledger → outbox.

Отсутствующая сущность пропускается без изменения порядка остальных. Порядок проверяется транзакционными тестами на deadlock.

### Формат событий

Оболочка события — snake_case по [топологии Kafka](../../system-design/architecture/messaging/19-kafka-topology.md): `event_id`, `event_type`, `schema_version`, `occurred_at`, `producer`, `correlation_id`, `data`. Поля `data` — camelCase. Топики: `fitness.challenge.finished.v1`, `fitness.coins.settlement_completed.v1`; ключ — `challengeId`.

## Данные и конфиденциальность

- Challenge владеет своими таблицами в `fitness_challenge`; Coins — в `fitness_coins`; межбазовых FK нет.
- В ответах не возвращаются токены, ключи идемпотентности, внутренние суммы других участников и служебные ID.
- `Idempotency-Key` не пишется в логи; тело запроса и Authorization не логируются.
- Чужой черновик и чужая операция скрываются под `404`.
- Профили, nickname и персональные данные Accounts в MVP не запрашиваются.
- Удаление challenge и участие в MVP не реализуются; retention идемпотентности не согласован.

## Критерии готовности

- [ ] Owner-контракты Challenge и gRPC ставок провалидированы; Gateway содержит только `$ref` на путь Challenge и публичный `503 challenge_unavailable`.
- [ ] Publish через Gateway: `200`/`202`; создатель стал участником, резерв подтверждён; при нехватке монет `409 insufficient_coins`, черновик жив.
- [ ] Join: `200`/`202`; место не блокируется на время проверки баланса; из двух параллельных join успешен ровно один, второй получает `409 challenge_full` после подтверждённого release.
- [ ] WHEN_FULL: после второго участия набор закрыт, challenge `ACTIVE`; до seal `GET` показывает `OPEN`, повтор seal не создаёт второй набор.
- [ ] Cancel создателем без других участников: `CANCELLED`, hold освобождён один раз; `refundStatus` `PENDING → COMPLETED` только после Coins.
- [ ] Внутренний результат: `SINGLE_WINNER`/`DRAW` фиксируются одной транзакцией с winners, digest и outbox; `GET result` до фиксации `PENDING`, после — `FINAL`.
- [ ] Settlement: победитель получает банк, при `DRAW` возвращаются ставки; `coins.settlement_completed` выпущен; проекция `COMPLETED` только после события.
- [ ] Повтор с тем же `Idempotency-Key` возвращает сохранённый результат; другое тело — `409 idempotency_key_conflict`.
- [ ] Потерянный ответ Coins разрешается по operation ID; recovery worker продолжает операцию после рестарта без второго резерва.
- [ ] Ошибки через Gateway в едином формате с `requestId`; без токена `401`; недоступность БД — `503`; неизвестный итог Coins — `202`, не `500` и не `503`.
- [ ] Внутренний `FinalizeResult` недоступен через публичный Gateway; прямой вызов без mTLS отклоняется.
- [ ] Maven verify и SpotBugs для challenge-service, coins-service, gateway-service; `git diff --check` и проверка Markdown-ссылок проходят или ограничение зафиксировано.
- [ ] Результаты и ограничения записаны в `verification.md`; Postman-коллекция дополнена сценариями challenge через Gateway.

## Открытые вопросы

- Подтвердить лимиты title, description, stakeAmount, durationDays.
- Подтвердить production-источник результата; в MVP используется доверенный внутренний gRPC с mTLS.
- Подтвердить размещение локального Kafka Compose.
- TTL и очистка `Idempotency-Key`, операций и release intents.
- Отдельные rate limit для challenge-операций.
- Поведение при длительной недоступности БД относительно дедлайна Gateway.
- Маппинг отсутствующего кошелька Coins (`WALLET_NOT_FOUND`).
- Добавление nickname и списка участников отдельной спецификацией.

## Процессные границы

Commit, push, merge, deploy и миграции пользовательской БД агент не выполняет без отдельного разрешения. Создание базы `fitness_challenge` и миграции `fitness_coins` требуют отдельного разрешения пользователя. Работа выполняется в существующей ветке `feature/50-challenge-service-dev`.
