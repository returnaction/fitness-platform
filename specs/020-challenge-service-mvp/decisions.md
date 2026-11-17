# Решения Spec 020

> **Спецификация:** [spec.md](spec.md)
> **Статус:** draft; решения фиксируются по мере согласования.
> **Ветка:** `feature/50-challenge-service-dev`

## Принятые рамки MVP

1. **Узкий срез.** Только `HEAD_TO_HEAD`, `PUBLIC`, `WHEN_FULL`, `min = max = 2`. `GROUP`, `PRIVATE`, `AT_SCHEDULE`, scheduler, апелляции, видео, модерация, уведомления, поиск и пагинация отложены.
2. **Номер и ветка.** Спецификация получает номер 020, потому что 019 занят `019-mobile-home-balance`. Работа идёт в существующей ветке `feature/50-challenge-service-dev`; новая ветка не создаётся.
3. **Без дней и заданий.** В MVP создаётся технический контур challenge без расписания, упражнений и снимков каталога. `durationDays` сохраняется для будущей совместимости, но не порождает `challenge_days`.
4. **`AWAITING_RESULTS` зарезервирован** в enum, но в MVP не используется.

## Данные и владение

5. **Coins — источник истины** балансов, holds, ledger и settlement. Challenge не изменяет `coin_wallets` и не принимает суммы от клиента.
6. **Создатель становится участником при публикации**, после подтверждения резерва; отдельного принятия создателем нет.
7. **Одна таблица операций Challenge** `challenge_operations` хранит `PUBLISH` и `JOIN`. Release отслеживается `challenge_release_intents`, seal — `challenge_stake_sets`; отдельные операции для них не создаются.
8. **Таблицы MVP явно ограничены** списком в `plan.md`. Дни, задания, апелляции, доступ, аудитории, journal, pending events и lifecycle jobs не создаются.
9. **Имена таблиц** соответствуют [модели 38](../../system-design/services/challenge/38-challenge-data-model.md) и [модели 36](../../system-design/services/coins/36-coins-data-model.md), а не сокращённым именам исходного черновика.

## Денежный контур

10. **Порядок вступления:** сначала `ReserveStake`, затем повторная проверка условий и атомарное занятие места под блокировкой строки challenge. Место не блокируется на время проверки баланса.
11. **Последнее место** защищено блокировкой challenge, счётчиком `next_join_sequence` и частичным уникальным индексом незавершённых операций.
12. **Потерянный ответ Coins не равен отказу.** Неизвестный результат оставляет операцию в `PENDING` и возвращает клиенту `202`; повторяется тот же operation ID.
13. **Таймерное освобождение hold запрещено.** Освобождение инициирует только Challenge через release intent; причина не меняется при повторе.
14. **Один release intent на hold** с `UNIQUE(hold_id)` и стабильным release operation ID.
15. **Отмена** доступна только создателю и только пока нет других подтверждённых участников. Протокол: `FreezeStakeSet` → release всех hold → `CompleteStakeCancellation`. `refundStatus` становится `COMPLETED` только после подтверждения Coins.
16. **`ListActiveHoldsForCancellation` не реализуется.** `CompleteStakeCancellation` сам проверяет отсутствие активных holds; HMAC-курсор отмены в MVP не нужен.
17. **Seal:** используется `BeginStakeSet` / `PutStakeBatch` / `FinalizeStakeSet`; одиночный `SealStakeSet` из исходного черновика не используется.
18. **Старт WHEN_FULL:** после подтверждения последнего места Challenge сохраняет набор и выполняет seal после commit. `ACTIVE` выставляется только после подтверждённого `FinalizeStakeSet`. До этого challenge остаётся `OPEN` с заполненными местами.
19. **Settlement MVP:** для двух holds расчёт выполняется одной локальной транзакцией Coins без escrow и универсального batch worker. `SINGLE_WINNER` получает банк, `DRAW` возвращает каждому его ставку; сумма выплат равна сумме holds.
20. **`MULTIPLE_WINNERS` отклоняется** внутренним `FinalizeResult` как неподдерживаемый исход.

## Контракты и события

21. **REST-контракт Challenge** — `contracts/openapi/challenge-v1.yaml`. Ставки не описываются OpenAPI: это внутренний gRPC.
22. **gRPC-контракты** размещаются в `contracts/proto` и генерируются модулем `fitness-grpc-contracts`. Черновики из `system-design/contracts` переносятся и дополняются.
23. **Внутренний результат** фиксируется gRPC `FinalizeResult` по mTLS. Публичного HTTP-метода и политики `SERVICE` в Gateway нет.
24. **Список победителей** не передаётся в событии целиком. Coins читает его через `ListResultWinners` и сверяет count/digest.
25. **События:** `challenge.finished` (Challenge → Coins) и `coins.settlement_completed` (Coins → Challenge). Имя `challenge.result_finalized` из исходного черновика не используется.
26. **Формат события:** snake_case-оболочка по [топологии Kafka](../../system-design/architecture/messaging/19-kafka-topology.md), поля `data` — camelCase. Топики `fitness.<event_type>.v1`, ключ `challengeId`.
27. **Имена HTTP-полей — camelCase** согласно инженерным стандартам. Snake_case-примеры дизайна 60 не копируются в контракт.
28. **Gateway** — единственный валидатор пользовательского JWT и сессии; Challenge читает forwarded Bearer JWT и очищает `SecurityContext`. В Gateway только `$ref` на контракт владельца и публичный `503 challenge_unavailable`.

## Надёжность и безопасность

29. **Двухуровневая идемпотентность:** клиентский `Idempotency-Key` для HTTP и server operation ID для gRPC. Очистка ключей и незавершённых операций в MVP не выполняется.
30. **Recovery worker** продолжает незавершённые publish/join/release/seal после рестарта. Он не является lifecycle-scheduler и не выполняет дедлайны дней.
31. **Outbox и inbox** обязательны: событие и локальное изменение — один commit; получатель проверяет `event_id` и digest; другой digest — карантин.
32. **mTLS:** Challenge вызывает Coins как `spiffe://fitness.local/challenge-service`; Coins вызывает Challenge для чтения победителей; источник результата имеет отдельный доверенный сертификат. Существующий interceptor Coins расширяется без ослабления правила «wallet creation только Identity».
33. **Локальные сертификаты:** `initialize-local-env.ps1` дополняется сертификатами Challenge и источника результата. Генерация должна добавлять отсутствующие материалы, не требуя полного набора и не заменяя CA.
34. **Правила ответов:** неизвестный результат Coins — `202`; окончательный `409 challenge_full` — только после подтверждённого release; `503` — когда операцию невозможно сохранить или прочитать из-за БД, а не когда итог неизвестен.
35. **Логи** не содержат токенов, `Idempotency-Key`, тел запросов, секретов и сумм чужих кошельков.

## Kafka и окружение

36. **Локальная Kafka** запускается отдельным Docker Compose в KRaft. Production `deployment/compose.yaml`, release scripts и серверная выкладка Challenge в эту спецификацию не входят.
37. **PostgreSQL остаётся вне Docker.** Создание `fitness_challenge` и роль `fitness_challenge_app` выполняются по отдельному разрешению.
38. **Версии:** используются управляемые Spring Boot 4.1.2 `spring-boot-starter-kafka`, Kafka-клиент 4.2.2, Testcontainers 2.0.5 с модулем `testcontainers-kafka`. Spotless не запускается.

## Предложено, требует подтверждения

39. Лимиты: title 1–120, description до 2000, `stakeAmount` 1–1 000 000, `durationDays` 1–30.
40. Локальный Kafka Compose размещается в `deployment/compose.local-kafka.yaml` и не используется на сервере.
41. **Отсутствие кошелька Coins:** gRPC возвращает `FAILED_PRECONDITION` с reason `WALLET_NOT_FOUND`, не маскируя его под `INSUFFICIENT_FUNDS` и не сохраняя как бизнес-исход. Публично Challenge отвечает `409 insufficient_coins` (решение пользователя 2026-11-17); отдельный публичный код потребует изменения `spec.md` и `challenge-v1.yaml`.
42. TTL идемпотентности и операций, rate limit и поведение при длительной недоступности БД остаются открытыми до отдельного решения.
43. **Корневые `AGENTS.md` и `specs/README.md` не изменяются** в рамках подготовки Spec 020 по решению пользователя. Статус спецификации хранится только в её собственном каталоге; активный scope в корневых файлах добавляется отдельным решением пользователя.
44. **Выбран вариант B — architecture-faithful MVP.** Пользователь 2026-11-17 выбрал сохранение Kafka, outbox/inbox, `BeginStakeSet`/`PutStakeBatch`/`FinalizeStakeSet`, `FreezeStakeSet`/`CompleteStakeCancellation`, `ListResultWinners` с курсором, детальных settlement-таблиц и lease/fencing. Lean-упрощения не применяются; возможные сокращения остаются кандидатами на будущие спецификации.
45. **Версия `fitness-grpc-contracts` остаётся `0.1.2`** для T002.2–T002.4 до T002.5: это одна неопубликованная подготовка контрактов, повторное обновление пяти потребителей не выполняется (решение пользователя 2026-11-17).
46. **`FinalizeResult` принимает `resultId`, `setVersion`, `setDigest`, `outcome`, `ruleVersion` и список победителей.** `winner_count` и `winners_digest` вычисляет Challenge из сохранённого списка и возвращает в ответе; вызывающий их не передаёт.
47. **Gateway для Challenge:** восемь HTTP-путей с политикой `AUTHENTICATED`, только `$ref` на `challenge-v1.yaml` без дублирования схем. Ответ upstream `503` публикуется как `challenge_unavailable`; внутренний gRPC `FinalizeResult` и `ListResultWinners` через Gateway недоступен.
