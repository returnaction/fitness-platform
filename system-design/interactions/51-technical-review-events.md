# События технического разбора видео — проект v1

Будущая Java / Spring-система; не реализация. Общая envelope, inbox/outbox и
Kafka key `submissionId` — в [событиях модерации](48-moderation-events.md).
Moderation владеет решением, Video Proof — обработкой, Challenge — итогом
задания. Имена топиков и сериализация ещё выбираются.

## 1. `video.technical_review_required`: Video Proof → Moderation

```json
{"eventId":"<uuid>","eventType":"video.technical_review_required","schemaVersion":1,
 "occurredAt":"2026-11-05T12:00:00Z","producer":"video-proof","correlationId":"<uuid>",
 "data":{"triggerId":"<uuid>","submissionId":"<uuid>","reasonCode":"ASSEMBLY_FAILED",
 "evidenceRef":{"schemaVersion":1,"assessmentId":"<uuid>"}}}
```

Факт и outbox сохраняются одной транзакцией Video Proof. Moderation через inbox
привязывает trigger к единственному активному делу отправки либо создаёт дело.
Повтор с другим event ID и тем же trigger/payload не создаёт дубль; конфликт
содержания — карантин. Несколько сигналов сохраняются отдельно по
[модели Moderation](../services/moderation/45-moderation-data-model.md#moderation_technical_triggers).
Ссылка содержит ID доказательства, а не
данные файла, секрет или URL. `ASSEMBLY_FAILED` не доказывает подмену.

## 2. `moderation.technical_decided`: Moderation → Video Proof и Challenge

```json
{"eventId":"<uuid>","eventType":"moderation.technical_decided","schemaVersion":1,
 "occurredAt":"2026-11-05T12:10:00Z","producer":"moderation","correlationId":"<uuid>",
 "data":{"caseId":"<uuid>","decisionId":"<uuid>","caseVersion":2,
 "submissionId":"<uuid>","decision":"RETRY_AFTER_FIX",
 "evidenceRef":{"schemaVersion":1,"assessmentId":"<uuid>"},
 "decidedAt":"2026-11-05T12:10:00Z"}}
```

`decision`: `INCONCLUSIVE`, `RETRY_AFTER_FIX`, `CONFIRMED_TAMPERING`.
`INCONCLUSIVE` не является окончательным результатом; Challenge продолжает
ожидать. `RETRY_AFTER_FIX` разрешает Video Proof создать одну новую версию
обработки по `decisionId`; создаётся локально с inbox и защитой уникальности.
`CONFIRMED_TAMPERING` Challenge применяет к корректной записи согласно правилам
после проверки актуальной версии дела; Video Proof сохраняет историю. Подозрение
не является доказательством. Полное правило достаточности доказательств и права
на решение ещё открыты. Event не содержит имени сотрудника, URL или ключей.

## 3. `video.technical_retry_completed`: Video Proof → Moderation и Challenge

```json
{"eventId":"<uuid>","eventType":"video.technical_retry_completed","schemaVersion":1,
 "occurredAt":"2026-11-05T12:20:00Z","producer":"video-proof","correlationId":"<uuid>",
 "data":{"caseId":"<uuid>","decisionId":"<uuid>","submissionId":"<uuid>",
 "processingGeneration":2,"outcome":"SUCCEEDED",
 "assessmentId":"<uuid>","completedAt":"2026-11-05T12:20:00Z"}}
```

`outcome`: `SUCCEEDED` или `BLOCKED`. Успех означает завершение технического
повтора, не проверку упражнения; готовый asset поступает далее по `video.ready`
и допуску Challenge. `BLOCKED` возвращает дело специалисту, не объявляя
мошенничество. Повтор события не создаёт повторного исхода. События в разных
топиках могут прийти в разном порядке: получатели сохраняют ожидание отсутствующей
зависимости и проверяют ID решения/поколения; никто не принимает результат
устаревшей обработки на основании только времени события.

Moderation под блокировкой дела проверяет, что `decisionId` относится к
последнему ожидаемому `RETRY_AFTER_FIX` и дело в `RESOLVING`. Для `SUCCEEDED`
дело переходит в `CLOSED`, при `BLOCKED` — в `ESCALATED` для нового разбора;
старое решение и evidence не перезаписываются. Inbox, изменение дела и аудит —
одна транзакция. Если до закрытия зафиксированы неразобранные новые сигналы,
дело остаётся `ESCALATED`: успешный повтор сам по себе не доказывает, что они
устранены. Новый trigger после закрытия создаёт новое дело. `video.ready` и
допуск остаются отдельными фактами; закрытие не означает успех упражнения.
Устаревшие или конфликтующие результаты идут на разбор без перезаписи состояния.

## Отказы и авторизация

Каждый получатель применяет сообщение через локальные inbox и бизнес-ключ;
outbox сохраняется в одной транзакции с изменением. Временный отказ базы —
ограниченный retry с мониторингом; повреждённая или неизвестная схема и
конфликт payload — карантин с управляемым повтором после устранения причины.
Права публикации ограничены identity производителя; секреты, подписанные ссылки,
персональные имена и видеобайты не включаются. Точные DTO реестра схем,
топики и ключи ротации ещё необходимо определить у владельцев. Проект ожидания
и уникальности повтора: [Video Proof](../services/video-proof/40-video-proof-service.md#video_outbox-и-входящие-события),
[Challenge](../services/challenge/38-challenge-data-model.md#challenge_inbox-и-challenge_pending_events).
Никакая проверка или доставка не считается доказанной без интеграционных тестов.
