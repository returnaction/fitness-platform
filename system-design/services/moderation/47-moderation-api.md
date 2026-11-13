# Moderation: проект внешнего API

Проект будущего Java / Spring-сервиса, не существующий API. Канонические
таблицы: [модель Moderation](45-moderation-data-model.md). Правила решений:
[вердикты](25-moderation-decisions.md). Бизнес-результат и право подать
апелляцию принадлежат Challenge; файлы принадлежат Video Proof.

## Общий контракт

Все пути начинаются с `/api/v1/moderation`. Запросы требуют действительный
access token и актуальную роль; конкретное назначение проверяется сервером.
Собственные идентификаторы Moderation и внешние ID дел/отправок — UUID; ID
упражнения и вариации в снимке задания — числа `1`–`32767`. Время — RFC 3339
UTC, JSON — UTF-8. Тело запроса не содержит ID модератора: сервер получает его
из проверенной identity. Клиенту не возвращаются email, nickname участника,
ставка или имя challenge.

Ошибки используют [общий формат](../identity/30-identity-registration.md):
`{"error":{"code":"case_conflict","message":"Карточка уже изменилась","requestId":"<uuid>"}}`.
`details` — необязательный массив `{field, code}`; HTTP-статус и `code`
стабильны для версии API, текст предназначен человеку.

Общие ошибки всех методов: `400 invalid_request`, `401 unauthenticated`,
`403 forbidden`, `404 case_not_found` для неизвестного или недоступного дела,
`429 rate_limited`, `500 internal_error`, `503 dependency_unavailable`.
`503` не доказывает, что предыдущая изменяющая операция не выполнилась.
Не выводить секреты, подписанные URL, SQL и stack trace в ошибки и логи.

Каждый POST ниже требует `Idempotency-Key` — UUID одной операции. Сервер
связывает ключ с автором, типом операции, целью и канонизированным payload;
повтор с теми же данными возвращает сохранённый статус и результат, с другими —
`409 idempotency_key_conflict`. Ключ сам не даёт права: при повторе сначала
проверяются действующая сессия и доступ. Сохранение результата, изменение дела,
аудит и outbox — одна локальная транзакция. Если решение уже принято другим
ключом, `409 case_already_decided`, второго решения нет.

Проект лимитов: текст причины после удаления пробелов — 1–2000 Unicode code
points для обязательной причины; для `APPROVE` допускается `null`, иначе
0–2000. Неподдерживаемые поля тела — `422 validation_error`; правила
нормализации и обработки невидимых символов до реализации уточнить.

## Очередь видео

### Получить список

`GET /api/v1/moderation/review-cases?limit=20&cursor=<cursor>` — право
`review.video.read`. `limit` 1–100, default 20; непрозрачный cursor привязан к
фильтрам, неверный — `400 invalid_cursor`. Предлагается keyset-сортировка по
`created_at, id`. Одновременно изменяющаяся очередь не является неизменяемым
снимком; клиент обновляет её повторным запросом. `200`:

```json
{"items":[{"caseId":"<uuid>","state":"OPEN","version":1,
"createdAt":"2026-11-05T10:00:00Z","exerciseName":"Push-up"}],
"nextCursor":null}
```

Очередь показывает доступные дела; открытие файла требует отдельного назначения
и проверки допуска. Собственную отправку сотрудник проверять не может: для
назначения и решения нужна доверенная проверка отсутствия конфликта интересов;
никакой identity участника при этом не показывается модератору. Владелец
проверки и отказ при его недоступности должны быть определены до реализации.
Формат курсора и его защита ещё определяются в реализации.

### Взять карточку

`POST /api/v1/moderation/review-cases/{case_id}/assign`, тело `{}`; право
`review.video.read`. `200`:

```json
{"caseId":"<uuid>","state":"IN_REVIEW","version":2,
"assignmentId":"<uuid>","assignedAt":"2026-11-05T10:00:00Z"}
```

`409 case_already_assigned`, `409 case_closed`, `409 admission_inactive`.
Проверка и назначение сериализуются по делу; один активный assignment защищён
уникальным индексом. Состояние проверки допуска должно быть актуальным;
ошибка внешнего сервиса — `503`, без утверждения об отзыве.

### Вернуть карточку

`POST /api/v1/moderation/review-cases/{case_id}/release`, тело
`{"caseVersion":2}`; только назначенный модератор либо уполномоченный
администратор. `200`: `{"caseId":"<uuid>","state":"OPEN","version":3}`.
`409 case_conflict`, `409 case_closed`; для чужого назначения — `403`.
Снятие назначения и решение конкурируют на одной строке и не могут оба
успешно изменить одну версию. Правило 20 минут обсуждалось для возврата дела;
автоматический таймер, смены и исключения ещё нуждаются в точном контракте.

### Детали и playback

`GET /api/v1/moderation/review-cases/{case_id}` — только назначенному
модератору с `review.video.read`. `200`:

```json
{"caseId":"<uuid>","state":"IN_REVIEW","version":2,
"asset":{"id":"<uuid>","generation":1},
"task":{"schemaVersion":1,"exerciseId":1,"variationId":1,"exerciseName":"Push-up",
"variationName":"Narrow grip","description":"Выполнить отжимания узким хватом",
"instructions":"Поставьте руки ближе ширины плеч и выполните отжимания.",
"target":{"metric":"REPETITIONS","value":10,"unit":"REP"}}}
```

DTO задачи — пример для повторений; вариант времени и ограничения:
[контракт метрик](../exercise-catalog/49-task-metric-contract.md). URL отдельно выдаёт Video Proof после проверки прав:
[контракт playback](../../interactions/46-moderation-playback-authorization.md).

### Сохранить решение

`POST /api/v1/moderation/review-cases/{case_id}/decisions`, право
`review.video.decide`, активное назначение. Запрос:

```json
{"caseVersion":2,"verdict":"DISAPPROVE_TECHNIQUE",
"reason":"Спина не сохраняет положение"}
```

Допустимые verdict: `APPROVE`, `DISAPPROVE_TECHNIQUE`,
`DISAPPROVE_WRONG_EXERCISE_OR_REPS`, `REJECT_UNSUITABLE_CONTENT`. Для всех,
кроме `APPROVE`, причина обязательна. `201 Created`:

```json
{"decisionId":"<uuid>","caseId":"<uuid>","verdict":"DISAPPROVE_TECHNIQUE",
"caseState":"DECIDED","decisionStatus":"RECORDED"}
```

`RECORDED` означает, что Moderation сохранила решение, а не что Challenge уже
применил его. `409 case_conflict`, `409 case_already_decided`,
`409 admission_inactive`, `422 validation_error`. При конкурирующих отзывах
допуска Challenge повторно проверяет версию до применения результата; решение
остаётся в аудите, даже если не применено. Факт применения и его видимость на
экране ещё требуют отдельного контракта.

## Апелляции

Право на апелляцию и её подача принадлежат Challenge. Moderation получает уже
принятую апелляцию по событию, не пересчитывая срок по времени получения Kafka.
Доступ — `appeal.read` или `appeal.decide`; пересмотреть собственное решение
запрещено, даже если у сотрудника есть обе роли.

| Метод | Тело | Ответ |
| --- | --- | --- |
| `GET /api/v1/moderation/appeal-cases?limit=20&cursor=...` | — | `200 {"items":[{"caseId":"...","state":"UNDER_REVIEW","version":1,"submittedAt":"...","reviewDueAt":"..."}],"nextCursor":null}` |
| `POST /api/v1/moderation/appeal-cases/{id}/assign` | `{}` | `200 {"caseId":"...","assignmentId":"...","caseVersion":2}` |
| `POST /api/v1/moderation/appeal-cases/{id}/release` | `{"caseVersion":2}` | `200 {"caseId":"...","caseVersion":3}` |
| `GET /api/v1/moderation/appeal-cases/{id}` | — | `200 {"caseId":"...","state":"UNDER_REVIEW","version":2,"originalVerdict":"DISAPPROVE_TECHNIQUE","originalReason":"...","assetId":"...","assetGeneration":1}` |
| `POST /api/v1/moderation/appeal-cases/{id}/decisions` | `{"caseVersion":2,"outcome":"UPHELD","reason":"..."}` | `201 {"decisionId":"...","caseId":"...","outcome":"UPHELD","decisionStatus":"RECORDED"}` |

`outcome`: `UPHELD` или `REJECTED`; причина обязательна. Дополнительные ошибки:
`409 case_already_assigned`, `409 case_conflict`, `409 case_already_decided`,
`403 own_decision_review_forbidden`, `422 validation_error`. Сохранённое
решение ещё должно быть применено Challenge. Истечение целевых 24 часов на
рассмотрение не создаёт автоматического решения.

## Технические дела

Права `technical_case.read` и `technical_case.decide`; подтверждение намеренной
подмены требует отдельного полномочия ответственного. Назначение, проверка
версии, идемпотентность и общие ошибки аналогичны карточкам видео.

| Метод | Тело | Ответ |
| --- | --- | --- |
| `GET /api/v1/moderation/technical-cases?limit=20&cursor=...` | — | `200 {"items":[{"caseId":"...","state":"OPEN","version":1,"reasonCode":"ASSEMBLY_FAILED"}],"nextCursor":null}` |
| `POST /api/v1/moderation/technical-cases/{id}/assign` | `{}` | `200 {"caseId":"...","assignmentId":"...","caseVersion":2}` |
| `POST /api/v1/moderation/technical-cases/{id}/release` | `{"caseVersion":2}` | `200 {"caseId":"...","caseVersion":3}` |
| `GET /api/v1/moderation/technical-cases/{id}` | — | `200 {"caseId":"...","state":"IN_REVIEW","version":2,"submissionId":"...","reasonCode":"ASSEMBLY_FAILED","evidenceRef":{"schemaVersion":1,"assessmentId":"..."}}` |
| `POST /api/v1/moderation/technical-cases/{id}/decisions` | `{"caseVersion":2,"decision":"INCONCLUSIVE","reason":"..."}` | `201 {"decisionId":"...","caseId":"...","state":"ESCALATED"}` |

Допустимы `INCONCLUSIVE`, `RETRY_AFTER_FIX`, `CONFIRMED_TAMPERING`. Последние
два возвращают `state: RESOLVING`, пока Video Proof или Challenge не обработал
решение. `INCONCLUSIVE` создаёт промежуточную запись, а не окончательный исход.
`evidenceRef` v1 содержит `schemaVersion=1` и `assessmentId` UUID; файлы и
секреты в нём не передаются. Коды причин и назначение ответственного ещё нужно
уточнить; обработка исхода повтора описана в [событиях](../../interactions/51-technical-review-events.md). Подробности:
[технический разбор](43-video-technical-review.md).

## События и отказ

`moderation.review_decided` v1: envelope `eventId`, `eventType`,
`schemaVersion`, `occurredAt`, `producer`, `correlationId`; data содержит
`decisionId`, `caseId`, `submissionId`, `taskId`, `admissionId`,
`admissionVersion`, `verdict`, `reason`, `decidedAt`. Ключ Kafka —
`submissionId`; топик и сериализация определены в
[каталоге событий](../../architecture/messaging/19-kafka-topology.md). Данные не содержат
идентичность модератора, URL, токены или видео. Challenge применяет событие с
inbox и проверяет версию допуска. Доставка может повториться; локальная запись
решения и outbox — одна транзакция. Проект событий апелляций и отзыва:
[события](../../interactions/48-moderation-events.md). Проект событий технического разбора:
[контракт](../../interactions/51-technical-review-events.md).

Канонический пример `moderation.review_decided` v1:

```json
{"eventId":"<uuid>","eventType":"moderation.review_decided","schemaVersion":1,
 "occurredAt":"2026-11-05T12:05:00Z","producer":"moderation","correlationId":"<uuid>",
 "data":{"decisionId":"<uuid>","caseId":"<uuid>","submissionId":"<uuid>",
 "taskId":"<uuid>","admissionId":"<uuid>","admissionVersion":1,
 "verdict":"DISAPPROVE_TECHNIQUE","reason":"Недостаточная амплитуда движения",
 "decidedAt":"2026-11-05T12:05:00Z"}}
```

Все показанные поля обязательны; `reason` — строка для отклонений, для
`APPROVE` допускается NULL. Словарь вердиктов — в [модели решений](25-moderation-decisions.md#согласованные-коды-решений-и-состояние-задания).
Время и причина являются данными решения, а не состоянием сессии модератора.
`decisionId` идентифицирует неизменяемое решение; при повторе один `eventId`
с другим payload — конфликт, а не новая версия. Challenge применяет его только
при актуальном допуске; неизвестная зависимость ожидает обработки, противоречие
передаётся на разбор. Устаревшее решение остаётся в аудите Moderation.

## Рабочая политика хранения

Согласовано для учебного v1: результат HTTP идемпотентности хранится минимум 90 суток после
завершения; незавершённые операции не очищаются. После срока сервер может не
восстановить исходный HTTP-ответ, но уникальность бизнес-решения по делу
сохраняется и не позволит вынести второе решение. Клиент должен открыть дело и
увидеть текущий статус. Проверить эту политику с retention событий и созданием
обратимых корректировок до реализации.

Решения и служебный аудит храним минимум 1 год после окончательного
закрытия дела, затем удалять по отдельной политике, не затрагивая ledger Coins.
Это согласованный учебный срок, не проверенный юридический срок хранения;
до реализации нужно определить фактические требования и обработку открытых
расследований. Видео удаляется по отдельному правилу хранения.

## Учебное допущение о сроке проверки

По решению пользователя в учебной модели предполагается, что модератор всегда
успевает проверить видео за 24 часа. Это допущение, а не гарантия работающей
системы или измеренный SLA. Отдельный сценарий отмены challenge из-за долгого
технического разбора и автоматического возврата ставок сейчас не вводится.

Технический разбор не приравнивается автоматически к проверке упражнения:
пока он открыт, применяются правила [технического разбора](43-video-technical-review.md).
Для ситуации, когда расследование действительно не удаётся завершить,
автоматического решения и новой бизнес-политики нет. Этот исключительный
случай отложен; истечение таймера само по себе не доказывает ни вину, ни
невиновность участника.

## Проверки

Реальный PostgreSQL: одновременное назначение, release против decision, два
решения, потеря ответа, повтор и конфликт ключа, решение собственного видео и
собственной апелляции, отзыв роли, откат транзакции. Kafka: повтор и задержка
событий, сбой между commit и offset, отзыв допуска в гонке с решением.
Права и S3: чужая карточка, URL, устаревшая генерация и отказ зависимостей.
Тесты не выполнялись; документ — проект, а не подтверждённая реализация.
