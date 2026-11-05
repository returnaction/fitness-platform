# Moderation: проект внешнего API

Проект будущего Java / Spring-сервиса, не существующий API. Канонические
таблицы: [модель Moderation](45-moderation-data-model.md). Правила решений:
[вердикты](25-moderation-decisions.md). Бизнес-результат и право подать
апелляцию принадлежат Challenge; файлы принадлежат Video Proof.

## Общий контракт

Все пути начинаются с `/api/v1/moderation`. Запросы требуют действительный
access token и актуальную роль; конкретное назначение проверяется сервером.
Идентификаторы — UUID, время — RFC 3339 UTC, JSON — UTF-8. Тело запроса не
содержит ID модератора: сервер получает его из проверенной identity. Клиенту
не возвращаются email, nickname участника, ставка или имя challenge.

Ошибки используют [общий формат](../identity/30-identity-registration.md):
`{"error":{"code":"case_conflict","message":"Карточка уже изменилась","request_id":"<uuid>"}}`.
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
{"items":[{"case_id":"<uuid>","state":"OPEN","version":1,
"created_at":"2026-11-05T10:00:00Z","exercise_name":"Push-up"}],
"next_cursor":null}
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
{"case_id":"<uuid>","state":"IN_REVIEW","version":2,
"assignment_id":"<uuid>","assigned_at":"2026-11-05T10:00:00Z"}
```

`409 case_already_assigned`, `409 case_closed`, `409 admission_inactive`.
Проверка и назначение сериализуются по делу; один активный assignment защищён
уникальным индексом. Состояние проверки допуска должно быть актуальным;
ошибка внешнего сервиса — `503`, без утверждения об отзыве.

### Вернуть карточку

`POST /api/v1/moderation/review-cases/{case_id}/release`, тело
`{"case_version":2}`; только назначенный модератор либо уполномоченный
администратор. `200`: `{"case_id":"<uuid>","state":"OPEN","version":3}`.
`409 case_conflict`, `409 case_closed`; для чужого назначения — `403`.
Снятие назначения и решение конкурируют на одной строке и не могут оба
успешно изменить одну версию. Правило 20 минут обсуждалось для возврата дела;
автоматический таймер, смены и исключения ещё нуждаются в точном контракте.

### Детали и playback

`GET /api/v1/moderation/review-cases/{case_id}` — только назначенному
модератору с `review.video.read`. `200`:

```json
{"case_id":"<uuid>","state":"IN_REVIEW","version":2,
"asset":{"id":"<uuid>","generation":1},
"task":{"schema_version":1,"exercise_id":"<uuid>","variation_id":"<uuid>","exercise_name":"Push-up",
"variation_name":"Narrow grip","description":"Выполнить отжимания узким хватом",
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
{"case_version":2,"verdict":"DISAPPROVE_TECHNIQUE",
"reason":"Спина не сохраняет положение"}
```

Допустимые verdict: `APPROVE`, `DISAPPROVE_TECHNIQUE`,
`DISAPPROVE_WRONG_EXERCISE_OR_REPS`, `REJECT_UNSUITABLE_CONTENT`. Для всех,
кроме `APPROVE`, причина обязательна. `201 Created`:

```json
{"decision_id":"<uuid>","case_id":"<uuid>","verdict":"DISAPPROVE_TECHNIQUE",
"case_state":"DECIDED","decision_status":"RECORDED"}
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
| `GET /api/v1/moderation/appeal-cases?limit=20&cursor=...` | — | `200 {"items":[{"case_id":"...","state":"UNDER_REVIEW","version":1,"submitted_at":"...","review_due_at":"..."}],"next_cursor":null}` |
| `POST /api/v1/moderation/appeal-cases/{id}/assign` | `{}` | `200 {"case_id":"...","assignment_id":"...","case_version":2}` |
| `POST /api/v1/moderation/appeal-cases/{id}/release` | `{"case_version":2}` | `200 {"case_id":"...","case_version":3}` |
| `GET /api/v1/moderation/appeal-cases/{id}` | — | `200 {"case_id":"...","state":"UNDER_REVIEW","version":2,"original_verdict":"DISAPPROVE_TECHNIQUE","original_reason":"...","asset_id":"...","asset_generation":1}` |
| `POST /api/v1/moderation/appeal-cases/{id}/decisions` | `{"case_version":2,"outcome":"UPHELD","reason":"..."}` | `201 {"decision_id":"...","case_id":"...","outcome":"UPHELD","decision_status":"RECORDED"}` |

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
| `GET /api/v1/moderation/technical-cases?limit=20&cursor=...` | — | `200 {"items":[{"case_id":"...","state":"OPEN","version":1,"reason_code":"ASSEMBLY_FAILED"}],"next_cursor":null}` |
| `POST /api/v1/moderation/technical-cases/{id}/assign` | `{}` | `200 {"case_id":"...","assignment_id":"...","case_version":2}` |
| `POST /api/v1/moderation/technical-cases/{id}/release` | `{"case_version":2}` | `200 {"case_id":"...","case_version":3}` |
| `GET /api/v1/moderation/technical-cases/{id}` | — | `200 {"case_id":"...","state":"IN_REVIEW","version":2,"submission_id":"...","reason_code":"ASSEMBLY_FAILED","evidence_ref":{"schema_version":1,"assessment_id":"..."}}` |
| `POST /api/v1/moderation/technical-cases/{id}/decisions` | `{"case_version":2,"decision":"INCONCLUSIVE","reason":"..."}` | `201 {"decision_id":"...","case_id":"...","state":"ESCALATED"}` |

Допустимы `INCONCLUSIVE`, `RETRY_AFTER_FIX`, `CONFIRMED_TAMPERING`. Последние
два возвращают `state: RESOLVING`, пока Video Proof или Challenge не обработал
решение. `INCONCLUSIVE` создаёт промежуточную запись, а не окончательный исход.
`evidence_ref` v1 содержит `schema_version=1` и `assessment_id` UUID; файлы и
секреты в нём не передаются. Коды причин и назначение ответственного ещё нужно
уточнить; обработка исхода повтора описана в [событиях](../../interactions/51-technical-review-events.md). Подробности:
[технический разбор](43-video-technical-review.md).

## События и отказ

`moderation.review_decided` v1: envelope `event_id`, `event_type`,
`schema_version`, `occurred_at`, `producer`, `correlation_id`; data содержит
`decision_id`, `case_id`, `submission_id`, `task_id`, `admission_id`,
`admission_version`, `verdict`, `reason`, `decided_at`. Ключ Kafka —
`submission_id`; топик и сериализация определены в
[каталоге событий](../../architecture/messaging/19-kafka-topology.md). Данные не содержат
идентичность модератора, URL, токены или видео. Challenge применяет событие с
inbox и проверяет версию допуска. Доставка может повториться; локальная запись
решения и outbox — одна транзакция. Проект событий апелляций и отзыва:
[события](../../interactions/48-moderation-events.md). Проект событий технического разбора:
[контракт](../../interactions/51-technical-review-events.md).

Канонический пример `moderation.review_decided` v1:

```json
{"event_id":"<uuid>","event_type":"moderation.review_decided","schema_version":1,
 "occurred_at":"2026-11-05T12:05:00Z","producer":"moderation","correlation_id":"<uuid>",
 "data":{"decision_id":"<uuid>","case_id":"<uuid>","submission_id":"<uuid>",
 "task_id":"<uuid>","admission_id":"<uuid>","admission_version":1,
 "verdict":"DISAPPROVE_TECHNIQUE","reason":"Недостаточная амплитуда движения",
 "decided_at":"2026-11-05T12:05:00Z"}}
```

Все показанные поля обязательны; `reason` — строка для отклонений, для
`APPROVE` допускается NULL. Словарь вердиктов — в [модели решений](25-moderation-decisions.md#согласованные-коды-решений-и-состояние-задания).
Время и причина являются данными решения, а не состоянием сессии модератора.
`decision_id` идентифицирует неизменяемое решение; при повторе один `event_id`
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
