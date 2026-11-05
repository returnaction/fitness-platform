> Исторический указатель до группировки документов. Актуальная навигация — [README](../README.md).

# System Design — учебная работа над архитектурой

Здесь мы изучаем существующее приложение и проектируем варианты его развития.
Это учебная работа: диаграммы и идеи сами по себе не означают, что мы меняем
текущий модульный монолит или планируем немедленно внедрять микросервисы.

В учебном размещении всё находится на одном сервере; границы и взаимодействия
проектируются так, чтобы позднее компоненты можно было вынести на разные машины.
Один сервер остаётся общей точкой отказа и не гарантирует независимость копий.

## С чего начать

Для общего обзора: [итог System Design](../overview/28-system-design-summary.md).
Инструкции для работы в этой папке: [AGENTS.md](../AGENTS.md).
Для работы команды: [инвентаризация и карта решений](../overview/29-document-and-decision-map.md).
Начало DDD: [Identity — регистрация](../services/identity/30-identity-registration.md).
Следующий сценарий: [Identity — вход и сессии](../services/identity/31-identity-login-sessions.md).
Следующий контекст: [Accounts — профиль](../services/accounts/32-accounts-profile.md).
Следующий контекст: [Coins — кошелёк и ежедневная награда](../services/coins/33-coins-wallet-and-reward.md).
Единый источник таблиц Coins: [модель данных Coins](../services/coins/36-coins-data-model.md).
Детали ставок: [gRPC и распределение банка](../services/coins/34-coins-stake-and-settlement-contract.md).
Большие challenge: [пошаговое распределение банка](../services/coins/35-coins-large-settlement.md).
Рабочая карта всех сервисов и незавершённых контрактов:
[карта контрактов](../overview/37-service-contracts-worklist.md).
Единый проект таблиц Challenge: [модель данных Challenge](../services/challenge/38-challenge-data-model.md).
Черновик запросов и ответов Challenge: [API Challenge](../services/challenge/39-challenge-api.md).
Следующий сервис: [Video Proof — данные и API](../services/video-proof/40-video-proof-service.md).
Взаимодействие сервисов для записи: [Challenge → Video Proof](../interactions/41-challenge-video-authorization.md).
Предлагаемый протокол срока: [доставка видео вовремя](../services/video-proof/42-video-upload-deadline.md).
Проект для специалиста: [технический разбор видео](../services/moderation/43-video-technical-review.md).
Проект передачи карточки: [Video Proof → Challenge → Moderation](../interactions/44-video-moderation-handoff.md).
Единые проектные таблицы: [модель Moderation](../services/moderation/45-moderation-data-model.md).
Межсервисный доступ к видео: [контракт просмотра](../interactions/46-moderation-playback-authorization.md).
Внешние запросы и ответы: [проект API Moderation](../services/moderation/47-moderation-api.md).
События: [апелляции и отзыв допуска](../interactions/48-moderation-events.md).
Единый формат задания: [метрики задания](../services/exercise-catalog/49-task-metric-contract.md).
Проверки и хранение: [сверка Moderation](../services/moderation/50-moderation-consistency-and-validation.md).
Контракт взаимодействия: [события технического разбора](../interactions/51-technical-review-events.md).
Проект хранения и проверок: [Kafka и дедлайн S3](../architecture/recovery/52-event-retention-and-validation.md).
Стратегия восстановления: [очистка и восстановление событий](../architecture/recovery/53-event-recovery-and-safe-cleanup.md).
Проект межсервисного восстановления: [API, Coins и backup](../architecture/recovery/54-event-recovery-contract.md).
Схемы и API: [снимки и сверка Coins](../architecture/recovery/55-recovery-snapshots-and-coins.md).
Расписание: [backup PostgreSQL и WAL](../architecture/recovery/56-postgresql-backup-plan.md).
Контракты: [Exercise Catalog](../services/exercise-catalog/57-exercise-catalog-service.md),
[Notifications](../services/notifications/58-notifications-service.md).
Внутренние вызовы профиля и сессии: [Identity ↔ Accounts](../interactions/59-identity-accounts-internal-api.md).
Расписание и доступ: [Challenge — расписание](../services/challenge/60-challenge-schedule-and-access.md).
Фоновый обработчик сроков: [Challenge — scheduler](../services/challenge/61-challenge-lifecycle-scheduler.md).
Архитектурное решение: [отдельный Java-проект и границы](../decisions/0001-new-project-and-domain-boundaries.md).
Архитектурное решение: [взаимодействие сервисов](../decisions/0002-service-communication.md).

Проектируемый backend — Java / Spring; существующий backend — Python / FastAPI.
Переход между ними будем разбирать отдельно.

1. [Диаграмма текущей системы](../overview/01-current-system.drawio) — компоненты по коду и
   staging-конфигурации; открыть в draw.io или diagrams.net.
2. [Архитектура проекта](../../architecture.md) — существующее описание устройства.
3. [Модель предметной области](../domain/domain-model.md) — копия исходной модели проекта
   для уточнения при проектировании сервисов. Актуальные учебные решения и
   расхождения с текущей реализацией — в итоговом документе и схемах.
4. [Backlog](../../backlog.md) — существующие и будущие задачи.
5. [Требования и предположения](../requirements/02-requirements.md) — правила бизнеса и учебная нагрузка.
6. [Словарь понятий](../domain/03-glossary.md) — общий язык для обсуждения бизнеса.
7. [Рабочие записи бизнес-правил](04-business-rules-notes.md) — сохранённые
   договорённости и примеры; пока содержат повторения и нуждаются в сверке.
8. [Открытые вопросы](../overview/05-open-questions.md) — решения, к которым вернёмся при
   проектировании конкретных сценариев.
9. [Будущий путь видео к модератору](../services/video-proof/06-future-proof-flow.drawio) — учебная
   схема предложенного взаимодействия; открыть в draw.io или diagrams.net.
10. [Общая карта будущей системы](../overview/07-future-system-overview.drawio) — крупные
    области, хранилища и предполагаемые связи для обсуждения.
11. [Вступление и резерв монет](../services/challenge/08-future-challenge-join.drawio) — учебный
    сценарий для будущих сервисов: сначала резерв монет, затем место.
12. [Будущий сервис Coins](../services/coins/09-future-coins.drawio) — граница сервиса, кошелёк,
    резервы ставок и неизменяемый журнал операций.
13. [Состояния challenge и участника](../services/challenge/10-challenge-states.drawio) — согласованная
    учебная модель состояний и переходов.
14. [Challenge и каталог](../services/exercise-catalog/11-challenge-catalog.drawio) — текущие сохранённые
    данные упражнения и предполагаемая будущая граница.
15. [Черновик событий Kafka](../architecture/messaging/12-kafka-event-contract-draft.md) — четыре события,
    их отправители, получатели, состав данных и открытые вопросы.
16. [Задачи видео: RabbitMQ](../architecture/messaging/13-rabbitmq-video-jobs-draft.md) — сравнение с
    текущим Redis/RQ и предлагаемый будущий путь обработки.
17. [Статус видео участника](../services/video-proof/14-user-proof-status.md) и
    [схема статуса](../services/video-proof/14-user-proof-status.drawio) — текущий запрос и будущая модель.
18. [Вход и API Gateway](../architecture/access/15-auth-gateway-draft.md) — предварительные границы
    Identity, единой точки входа и проверок прав сервисами.
19. [Масштабирование сервисов](../architecture/deployment/16-service-scaling.md) — копии сервисов,
    разделение состояния и безопасность параллельной работы.
20. [Учебный стенд на одном сервере](../architecture/deployment/17-single-server-deployment.drawio) —
    предполагаемые контейнеры и ограничения физического размещения.
21. [Coins: основная база и реплика](../services/coins/18-coins-db-failover.drawio) — учебная
    схема синхронного подтверждения без переключения реплики в основную.
22. [Учебная топология Kafka](../architecture/messaging/19-kafka-topology.md) — издатели, получатели,
    ключи и ограничения порядка для четырёх событий.
23. [Вступление: контракт Challenge и Coins](../services/challenge/20-join-service-contract-draft.md)
    — синхронная команда, идемпотентность и восстановление операции.
24. [Отмена challenge и возврат ставок](../services/challenge/21-challenge-cancellation-draft.md) —
    будущие границы, повторы и незавершённые вступления.
25. [Статус архитектуры](../overview/22-architecture-status.md) — что выбрано, что ещё
    требуется и в каком порядке продолжать.
26. [Доступ к видео в S3](../services/video-proof/23-video-storage-access.md) — загрузка и просмотр,
    проверка прав, временные ссылки и открытые вопросы.
27. [Приём видео и восстановление](../services/video-proof/24-video-acceptance-recovery.md) — граница
    между S3 и PostgreSQL, повторы и нерешённые сбои.
28. [Четыре решения модератора](../services/moderation/25-moderation-decisions.md) — границы,
    последствия для участника, события и совместимость с текущим приложением.
29. [Карта контекстов DDD](../domain/27-ddd-context-map.drawio) — области ответственности
    будущей системы и смысл связей между ними.

## Как будем работать

Сначала разберём, как система работает сегодня, и нарисуем её основные части и
связи. Затем выберем конкретный пользовательский сценарий и проследим его путь
через приложение. После этого будем обсуждать границы бизнес-областей, возможные
нагрузки, узкие места и варианты изменений. Предположения отмечаем отдельно от
фактов, проверенных по коду или настройкам.

Диаграммы храним в формате `.drawio`, чтобы их можно было редактировать и
отслеживать изменения в Git. В документы не добавляем пароли, ключи и токены.
Дополнительные диаграммы и записи решений создаём по мере необходимости.

Я сам написал:
DAU 10000 пользователей. Все участвуют.
2 000 участников один на один × 1 видео = 2 000 видео
8 000 участников в группах    × 1 видео = 8 000 видео
                                           ────────────
Всего:                                    10 000 видео в день


Нагрузка:       10 000 активных участников в день
Встречи 1 на 1: 2 000 участников
Группы:         8 000 участников
Видео:          10 000 в день
Длина видео:    15 минут
Рост:           20% в год — если это предположение сохраняем

15-минутное видео будет весить примерно 464 МБ, или 443 МиБ.
видео — 4 Мбит/с;
звук — 0,128 Мбит/с;
продолжительность — 900 секунд.

(4 + 0,128) Мбит/с × 900 секунд ÷ 8 ≈ 464 МБ

1 видео              ≈ 464 МБ
10 000 видео в день  ≈ 4,64 ТБ в день

Для нас я бы взял такие:
