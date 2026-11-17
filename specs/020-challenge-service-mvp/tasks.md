# Задачи Spec 020

> **Спецификация:** [spec.md](spec.md)
> **План:** [plan.md](plan.md)
> **Статус:** draft
> **Ветка:** `feature/50-challenge-service-dev`
> **Режим:** после каждой задачи — отчёт с доказательствами и остановка на ревью. Commit, push, merge, deploy и миграции выполняет пользователь.

## Подготовка

- [x] T001 — Зафиксировать исправленную спецификацию 020 в репозитории. Зависит от согласия пользователя. Критерий: `spec.md`, `plan.md`, `tasks.md`, `decisions.md`, `questions.md`, `verification.md` созданы; `AGENTS.md` и `specs/README.md` не изменяются по решению пользователя; ссылки проверены.
- [x] T002.1 — Owner OpenAPI Challenge. Зависит от T001. Критерий: `contracts/openapi/challenge-v1.yaml` описывает draft/patch/publish/join/cancel/read/operations/result, camelCase, Bearer, обязательный `Idempotency-Key`, ответы `200/201/202` со `statusUrl`, `refundStatus`, `settlementStatus`, ошибки с `requestId`.
- [x] T002.2 — gRPC-контракт ставок. Зависит от T002.1. Критерий: `contracts/proto/coins-stake-v1.proto` содержит Reserve/Release/GetStakeOperation, Begin/Put/Finalize, Freeze/CompleteCancellation, бизнес-исходы и ошибки operation ID; версия `fitness-grpc-contracts` повышена.
- [x] T002.3 — gRPC-контракт результата. Зависит от T002.2. Критерий: `contracts/proto/challenge-results-v1.proto` содержит `FinalizeResult` и `ListResultWinners`, поля digest/count/outcome, правила mTLS-авторизации и конфликта resultId.
- [x] T002.4 — Gateway OpenAPI. Зависит от T002.3. Критерий: `contracts/openapi/gateway-v1.yaml` содержит только `$ref` на путь Challenge, protected paths и публичный `503 challenge_unavailable`, без дублирования схем.
- [x] T002.5 — Проверка контрактов и потребителей. Зависит от T002.4. Критерий: `openapi-spec-validator` проходит; все пять потребителей `fitness-grpc-contracts` используют одну версию; индекс `contracts/README.md` обновлён.

## Реализация

### Gateway

- [x] T003.1 — Добавить `UpstreamId.CHALLENGE` и обязательную конфигурацию URL. Зависит от T002.5. Критерий: приложение не стартует без адреса Challenge; тесты свойств обновлены.
- [x] T003.2 — Обновить конфигурацию по средам. Зависит от T003.1. Критерий: `application.yaml`, `.env.example`, `test-environment.properties`, `deployment/env/gateway.env.example` содержат `CHALLENGE_HTTP_BASE_URL`.
- [x] T003.3 — Добавить маршруты Challenge в каталог. Зависит от T003.2. Критерий: drafts/patch/publish/joins/cancel/get/operations/result зарегистрированы с `AUTHENTICATED`; внутренний результат отсутствует в каталоге.
- [x] T003.4 — Переработать тесты каталога и security forwarding. Зависит от T003.3. Критерий: таблица ожидаемых операций вместо `hasSize(8)`; подтверждены JWT validation, CheckSession, forwarding `Authorization` и канонического `X-Request-Id`.
- [x] T003.5 — Проверить динамические пути через proxy. Зависит от T003.4. Критерий: mock upstream получает `/api/v1/challenges/{challengeId}` и `/api/v1/challenge-operations/{operationId}`; статус, тело и заголовки проксируются; query string отклоняется.
- [x] T003.6 — Verify gateway-service. Зависит от T003.5. Критерий: `./mvnw -B -ntp -pl services/gateway-service -am verify` и SpotBugs проходят.

### Challenge: основа и чтение

- [x] T004.1 — Зависимости и конфигурация модуля. Зависит от T003.6. Критерий: добавлены JPA, Liquibase, PostgreSQL, validation, MapStruct, gRPC, Kafka, Testcontainers; строгий Jackson как в Accounts; приложение стартует в тестах.
- [x] T004.2 — Миграции `fitness_challenge`. Зависит от T004.1 и отдельного разрешения на БД. Критерий: созданы только таблицы из `plan.md`; CHECK и уникальные индексы соответствуют спеке; пустая БД поднимается Liquibase.
- [x] T004.3 — Доменные состояния и переходы. Зависит от T004.2. Критерий: `DRAFT→OPEN→ACTIVE→FINISHED` и `OPEN→CANCELLED` сериализуются; некорректные переходы отклоняются; статусы участия соответствуют спеке.
- [x] T004.4 — Repository-слой и блокировки. Зависит от T004.3. Критерий: чтение под pessimistic lock, `version`, `next_join_sequence`, частичный уникальный индекс незавершённых операций, `UNIQUE(hold_id)` release intent.
- [x] T004.5 — Claims-фильтр и security chain. Зависит от T004.4. Критерий: `sub` forwarded Bearer JWT попадает в `SecurityContext`; контекст очищается; Actuator доступен по текущим правилам; неизвестный путь закрыт.
- [x] T004.6 — Общий error handler и security handler. Зависит от T004.5. Критерий: формат `{"error":{code,message,requestId}}`, коды по спеке, `401/404/409/422/503`, без внутренних подробностей.
- [ ] T004.7 — Хранение HTTP-идемпотентности. Зависит от T004.6. Критерий: ключ привязан к пользователю, методу и пути; повтор возвращает сохранённый ответ; другое тело — `409 idempotency_key_conflict`.
- [ ] T004.8 — DTO и endpoints draft/patch/read/operations. Зависит от T004.7. Критерий: `201` с `Location`, `200`, `409 version_conflict`, `422`; чужой DRAFT — `404`; чужая операция — `404`; `refundStatus` и `settlementStatus` в чтении.

### Денежный контур

- [ ] T005.1 — Миграции Coins. Зависит от T002.2 и отдельного разрешения на миграции. Критерий: созданы таблицы holds, ledger, stake set, operations, sealed stakes, settlement, inbox/outbox; существующий кошелёк не изменён.
- [ ] T005.2 — ReserveStake. Зависит от T005.1. Критерий: одна транзакция обновляет available/locked, создаёт hold и ledger; идемпотентность по operation ID; исходы RESERVED, INSUFFICIENT_FUNDS, ACTIVE_HOLD_EXISTS, WALLET_NOT_FOUND.
- [ ] T005.3 — ReleaseStake. Зависит от T005.2. Критерий: hold освобождается ровно один раз, ledger-запись создаётся, повтор с тем же ID возвращает сохранённый исход, другой ID для освобождённого hold отклоняется.
- [ ] T005.4 — Begin/Put/Finalize набора. Зависит от T005.3. Критерий: состояние набора сериализуется, пакеты 1–500, digest и активные holds сверяются, повтор безопасен, новый reserve после SEALED запрещён.
- [ ] T005.5 — Freeze и CompleteStakeCancellation. Зависит от T005.4. Критерий: freeze запрещает новые резервы, complete не проходит при активном hold, повтор идемпотентен, состояние `CANCELLED` фиксируется.
- [ ] T004.9 — gRPC-клиент Coins. Зависит от T005.2, T005.5. Критерий: mTLS, deadline меньше gateway 7s, классификация `UNAVAILABLE`/`DEADLINE_EXCEEDED` как неизвестного исхода, повторы по operation ID.
- [ ] T004.10 — Publish. Зависит от T004.8, T004.9. Критерий: durable PUBLISH, резерв до `OPEN`, создатель `JOINED`, `200`/`202`, `409 insufficient_coins`/`stake_conflict`, черновик жив при отказе.
- [ ] T004.11 — Join. Зависит от T004.10. Критерий: reserve до места, повторная проверка под блокировкой, атомарный `join_sequence`, `409 already_joined/challenge_full/challenge_started/challenge_cancelled`, release при неудаче.
- [ ] T004.12 — Cancel и release intents. Зависит от T004.11. Критерий: только создатель и только без других участников; один intent на hold; `PENDING→COMPLETED` только после Coins; retry безопасен.
- [ ] T004.13 — Recovery worker. Зависит от T004.12. Критерий: продолжает publish/join/release/seal после рестарта, использует lease и `FOR UPDATE SKIP LOCKED`, не освобождает hold по таймеру.

### Старт, результат и события

- [ ] T004.14 — Seal и WHEN_FULL. Зависит от T005.4, T004.13. Критерий: после второго участия набор закрывается, затем `ACTIVE`; до seal challenge `OPEN`; повтор не создаёт второй seal.
- [ ] T004.15 — Outbox publisher. Зависит от T004.14. Критерий: событие и локальное изменение — один commit; claim с lease; повтор не дублирует событие; backlog наблюдаем.
- [ ] T004.16 — gRPC-сервер результата. Зависит от T004.15. Критерий: `FinalizeResult` пишет result/winners/digest/outbox одной транзакцией; `MULTIPLE_WINNERS` отклоняется; повтор по `resultId` безопасен; `ListResultWinners` доступен Coins по mTLS.
- [ ] T004.17 — Consumer settlement с inbox. Зависит от T004.16, T005.6. Критерий: проекция `COMPLETED` только после `coins.settlement_completed`; тот же `event_id` с другим digest — карантин; неизвестный результат не применяется.
- [ ] T005.6 — Settlement Coins. Зависит от T005.5, T004.16. Критерий: `challenge.finished` создаёт settlement; победители проверяются по digest; `SINGLE_WINNER` получает банк, `DRAW` — возврат; событие завершения в outbox.
- [ ] T005.7 — Outbox publisher Coins. Зависит от T005.6. Критерий: публикация `coins.settlement_completed` после commit, повтор и lease безопасны.

### Тесты

- [ ] T004.18 — Repository-тесты Challenge. Зависит от T004.17. Критерий: блокировки, version, уникальность последнего места, release intent, операции и outbox.
- [ ] T004.19 — Web/security-тесты Challenge. Зависит от T004.18. Критерий: `200/201/202/400/401/404/409/422/503`, идемпотентность, формат ошибок, `requestId`, чужой draft.
- [ ] T004.20 — Concurrency и failure-тесты. Зависит от T004.19. Критерий: два join за последнее место, cancel против join, потерянный ответ Coins до/после commit, рестарт recovery, повтор seal.
- [ ] T004.21 — Verify challenge-service. Зависит от T004.20. Критерий: `./mvnw -B -ntp -pl services/challenge-service -am verify` и SpotBugs проходят.
- [ ] T005.8 — Тесты Coins. Зависит от T005.7. Критерий: гонки reserve/seal/release/freeze, потерянные ответы, двойной release исключён, settlement идемпотентен.
- [ ] T005.9 — Verify coins-service. Зависит от T005.8. Критерий: `./mvnw -B -ntp -pl services/coins-service -am verify` и SpotBugs проходят.

## Сквозная проверка и завершение

- [ ] T006.1 — Локальная Kafka и тестовые данные. Зависит от T004.21, T005.9. Критерий: локальный Compose KRaft поднимается; две сессии и два кошелька с монетами подготовлены без записи секретов в Git.
- [ ] T006.2 — Publish и join через Gateway. Зависит от T006.1. Критерий: `200/202`, резервы видны в Coins; после второго участия и seal challenge `ACTIVE`.
- [ ] T006.3 — Гонка последнего места. Зависит от T006.2. Критерий: ровно один подтверждённый участник; второй получает `409` только после release; повтор не создаёт второй резерв.
- [ ] T006.4 — Отмена и возврат. Зависит от T006.3. Критерий: `CANCELLED`, hold освобождён один раз, `refundStatus` `PENDING→COMPLETED`.
- [ ] T006.5 — Результат и settlement. Зависит от T006.4. Критерий: `GET result` `FINAL`; баланс победителя увеличен; `settlementStatus COMPLETED`; повтор события не меняет баланс.
- [ ] T006.6 — Security и ошибки через Gateway. Зависит от T006.5. Критерий: `401` без токена; внутренний gRPC недоступен через Gateway; `503` при недоступности БД; `202` при неизвестном ответе Coins.
- [ ] T006.7 — Записать доказательства. Зависит от T006.6. Критерий: команды, `requestId`, результаты и ограничения в `verification.md`.
- [ ] T006.8 — Обновить Postman. Зависит от T006.7. Критерий: сценарии challenge через Gateway с проверками; токены и секреты не экспортируются.
- [ ] T007 — Итоговые проверки. Зависит от T006.8. Критерий: verify затронутых модулей, SpotBugs, `git diff --check`, проверка Markdown-ссылок; контракты и документы отражают фактическое поведение; статус спецификации обновлён.

## Связанные Pull Request

| Задача | Ветка | Pull Request | Статус |
| --- | --- | --- | --- |
| Спецификация 020 | `feature/50-challenge-service-dev` | — | планируется |
