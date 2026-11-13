# Moderation и Challenge: события апелляции и допуска

Проект контракта v1 будущей Java / Spring-системы; не действующие события.
Challenge владеет правом апелляции и допуском; Moderation — карточкой и
решениями. Определения таблиц: [Challenge](../services/challenge/38-challenge-data-model.md),
[Moderation](../services/moderation/45-moderation-data-model.md). Таблицы права
и допуска уже описаны в модели Challenge; ограничения и конкурентное поведение
требуют проверки до миграций.

## Общая оболочка

Все события JSON UTF-8 в проекте v1, время RFC 3339 UTC, идентификаторы UUID.
Названия топиков и реестр схем ещё не выбраны. Обязательные поля: `eventId`,
`eventType`, `schemaVersion` = 1, `occurredAt`, `producer`, `correlationId`,
`data`. `eventId` неизменен при повторной публикации. Kafka key — `submissionId`
в событиях ниже; общая транзакция между сервисами и Kafka отсутствует.

Запись факта и outbox — один commit отправителя; получатель сохраняет inbox,
изменение и, при необходимости, собственный outbox одним commit. Offset Kafka
продвигается после commit без пропуска предыдущих записей партиции. Повтор того
же ID и содержимого — no-op; тот же ID с другим payload — карантин и сигнал.
Другой event ID не отменяет проверку уникальности бизнес-операции. Ошибка БД
повторяется с задержкой; недопустимая схема — карантин, не бесконечный retry.

## 1. `appeal.submitted` v1: Challenge → Moderation

```json
{"eventId":"<uuid>","eventType":"appeal.submitted","schemaVersion":1,
 "occurredAt":"2026-11-05T12:00:00Z","producer":"challenge","correlationId":"<uuid>",
 "data":{"appealId":"<uuid>","submissionId":"<uuid>","taskId":"<uuid>",
 "originalDecisionId":"<uuid>","submittedAt":"2026-11-05T12:00:00Z",
 "reviewDueAt":"2026-11-06T12:00:00Z","appealVersion":1}}
```

Challenge принимает подачу и проверяет её срок атомарно по своим серверным
часам. Moderation создаёт карточку по `appealId` единожды; задержка Kafka не
отменяет своевременную подачу. Если исходное решение ещё не пришло в локальную
базу Moderation, событие надёжно ожидает зависимости с оповещением, а не
отбрасывается. Проект хранения: `moderation_pending_events` в
[модели Moderation](../services/moderation/45-moderation-data-model.md#moderation_pending_events--проект).

## 2. `appeal.decided` v1: Moderation → Challenge

```json
{"eventId":"<uuid>","eventType":"appeal.decided","schemaVersion":1,
 "occurredAt":"2026-11-05T13:00:00Z","producer":"moderation","correlationId":"<uuid>",
 "data":{"appealId":"<uuid>","appealDecisionId":"<uuid>","originalDecisionId":"<uuid>",
 "submissionId":"<uuid>","taskId":"<uuid>","outcome":"UPHELD",
 "reason":"Техника соответствует заданию","decidedAt":"2026-11-05T13:00:00Z",
 "appealVersion":1}}
```

`outcome` — `UPHELD` либо `REJECTED`. Причина обязательна и ограничена API
Moderation. Challenge проверяет связь апелляции с исходным решением и своей
версией права, применяет результат один раз и пересчитывает состояния заданий
и участника. Неизвестная апелляция или конфликт не перезаписывает результат:
сообщение ожидает зависимость либо попадает на технический разбор. Если другое
задание дня окончательно провалено, применяется прекращение из раздела ниже;
нельзя автоматически принять решение как восстанавливающее участника.

## 2a. `appeal.cancelled` v1: Challenge → Moderation

```json
{"eventId":"<uuid>","eventType":"appeal.cancelled","schemaVersion":1,
 "occurredAt":"2026-11-05T13:05:00Z","producer":"challenge","correlationId":"<uuid>",
 "data":{"appealId":"<uuid>","submissionId":"<uuid>","taskId":"<uuid>",
 "originalDecisionId":"<uuid>","appealVersion":2,
 "reasonCode":"OTHER_TASK_FINAL_FAILED","cancelledAt":"2026-11-05T13:05:00Z"}}
```

Challenge отменяет находящуюся на рассмотрении апелляцию только когда другое
обязательное задание того же дня окончательно провалено и уже не может быть
оспорено. Изменение права и апелляции и outbox — один commit. `CANCELLED` не
равен вердикту `REJECTED`. Moderation атомарно сохраняет inbox, guard версии,
закрывает карточку и активное назначение; ранее записанное решение не стирается.
Если отмена пришла до подачи, guard не даст более старой подаче открыть карточку.

Гонка: Challenge сериализует отмену и применение вердикта по состоянию права
и дня. Если отмена зафиксирована первой, поздний вердикт не меняет результат,
но сохраняется в аудите Moderation. Если вердикт применён первым, он остаётся
в истории; окончательно проваленное другое задание всё равно учитывается при
пересчёте дня. Второй отменой не переписываем уже окончательное решение.
Повтор сообщения с тем же payload безопасен; конфликт версии уходит на разбор.

## 3. `proof.review_admission_revoked` v1: Challenge → Moderation

```json
{"eventId":"<uuid>","eventType":"proof.review_admission_revoked","schemaVersion":1,
 "occurredAt":"2026-11-05T13:10:00Z","producer":"challenge","correlationId":"<uuid>",
 "data":{"submissionId":"<uuid>","taskId":"<uuid>","admissionId":"<uuid>",
 "admissionVersion":2,"revokedAt":"2026-11-05T13:10:00Z","reasonCode":"PARTICIPATION_ENDED"}}
```

`admissionVersion` — новая версия состояния допуска; `reasonCode` — проектный
стабильный код, полный словарь ещё не утверждён. Для одной отправки Challenge
монотонно увеличивает версию; ревокация фиксируется с outbox одним commit.
Moderation сохраняет последнюю известную версию, закрывает открытую карточку и
активное назначение, если событие новее. Уже принятое решение не стирается.

Если отзыв пришёл раньше события допуска, Moderation должен надёжно сохранить
tombstone `(submission_id, admission_id, version)` и не создавать карточку по
более старой версии. Если события одной версии противоречат друг другу —
карантин. Отсутствующая версия, повторный допуск после отзыва и возможная смена
`admissionId` требуют отдельной процедуры восстановления; нельзя просто
увеличить число партиций и предположить прежний порядок. Проект v1 использует
стабильный `admissionId` на submission и [guard / pending](../services/moderation/45-moderation-data-model.md#moderation_admission_guards--проект);
его ещё требуется согласовать со схемой Challenge и проверить на гонках.

Отзыв и исходное решение могут пересечься: Moderation сохраняет свою историю,
а Challenge под локальной блокировкой сверяет актуальный допуск при применении
решения. Устаревшее решение не меняет результат. Уже выданный URL может
оставаться действительным: [доступ](46-moderation-playback-authorization.md).

## Валидация и конфиденциальность

Все UUID обязательны, версии положительны, дата решения не заменяет локальную
проверку жизненного цикла. События не содержат имён модератора или участника,
email, ставок, видеобайтов и подписанных URL. `reason` — текст, который
предназначен участнику; нельзя помещать в него служебные секреты. Доступ к
топикам ограничивается service identity. Схему и совместимость новых полей
нужно проверить реестром схем при выборе технологии; описание не заменяет
протокол доставки или авторизацию.
