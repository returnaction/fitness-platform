# Spec 017: backend API баланса Coins через HTTP

> **Статус:** completed — реализация и локальная сквозная проверка выполнены
> **Ветка:** `feature/48-017-coins-wallet-realtime`
> **Дата:** 2026-11-14
> **Документы:** [план](plan.md), [задачи](tasks.md), [вопросы](questions.md), [проверка](verification.md).

## Цель

Предоставить защищённый HTTP API актуального баланса через маршрут
`gateway-service → coins-service`.

## Подтверждённое состояние

Сверка по содержимому файлов и проектным документам на 2026-11-14; запуск
сервисов не выполнялся. Проверка документов T001 завершена:

| Компонент | Есть сейчас | Отсутствует для задачи |
| --- | --- | --- |
| [coins-service](../../services/coins-service/) | gRPC `CreateWalletIfAbsent`, `WalletEntity` (available/locked) | REST API баланса |
| [gateway-service](../../services/gateway-service/) | WebMVC servlet-прокси, дедлайн запроса, CheckSession для защищённых запросов; каталог IDENTITY/ACCOUNT | Маршрут COINS |
| [contracts](../../contracts/) | Proto создания кошелька; OpenAPI identity/accounts/gateway/coins; Coins подключён через Gateway $ref | Реализация и runtime-проверка контракта |

Coins владеет балансом и остаётся источником истины.

Сверка T001: `WalletEntity.userId` — первичный ключ; оба баланса имеют тип
`long`, в миграции — `BIGINT NOT NULL` с ограничением неотрицательности.
`WalletRepository` уже наследует `findById(UUID)` от `JpaRepository` и содержит
`insertIfAbsent`; дополнительный метод чтения без необходимости не нужен.
HTTP security Coins сейчас разрешает actuator и закрывает остальные запросы.
В Coins есть RequestIdFilter/Resolver, но нет REST-контроллера баланса,
фильтра извлечения claims и общего HTTP-обработчика ошибок.
Gateway пересылает Authorization для защищённых маршрутов и канонический
X-Request-Id; upstream COINS отсутствует также в основной конфигурации.
После T002 OpenAPI Gateway ссылается на `accounts-profile-v1.yaml` и
`coins-wallet-v1.yaml`; оба owner-контракта проходят валидатор.
Accounts извлекает UUID subject и роли без проверки подписи, очищает контекст
после запроса и обрабатывает ошибки фильтра через SecurityExceptionHandler.
Этот путь ошибок надо сохранить в T004: одного RestControllerAdvice для
ошибок до контроллера недостаточно.

## Согласованный объём

1. Контракт владельца — `contracts/openapi/coins-wallet-v1.yaml`. Он описывает
   endpoint, Bearer security, `int64`-балансы, заголовок `Cache-Control` и
   ошибки. `contracts/openapi/gateway-v1.yaml` содержит только `$ref` на путь
   Coins, protected path и публичный ответ `503 coins_unavailable`; схемы Coins
   в Gateway не дублируются.
2. Gateway: upstream COINS и маршрут
   `GET /api/v1/coins/me, COINS, AUTHENTICATED` в каталоге. Сохраняются
   существующие дедлайн, JWT-проверка и CheckSession; `Authorization`
   пересылается в Coins. Gateway реализуется и проверяется с mock upstream до
   начала работы в Coins.
3. Coins: `GET /api/v1/coins/me` возвращает
   `{"availableBalance":10,"lockedBalance":20}`. Оба значения — JSON
   `integer` формата `int64`; ответ и ошибки содержат `Cache-Control: no-store`.
   Владелец определяется из `sub` уже проверенного Gateway Bearer JWT. Coins
   повторно не валидирует подпись JWT и сессию: он извлекает claims и заполняет
   `SecurityContext` по паттерну Accounts.
4. Coins использует локальный эквивалент существующих `ApiErrorResponse` и
   `ApiExceptionHandler`, без нового общего модуля. Ошибки: `401
   invalid_access_token`, `500 wallet_invariant_violation` для отсутствующего
   кошелька активного пользователя, `503 coins_unavailable` для временной
   ошибки БД/транзакции и `500 internal_error` для непредвиденной ошибки.

## Вне объёма

Постоянные каналы обновлений, серверное развёртывание, Docker и его сеть,
история операций, daily-reward claim, ставки и settlement
— отдельные задачи. Не входят административные операции, миграции
пользовательских БД без отдельного разрешения, а также commit, push, merge и
deploy агентом.

## Сценарии и критерии готовности

- [ ] GET через Gateway с действующей сессией возвращает 200 и баланс владельца;
  без сессии — 401; чужой баланс недоступен.
- [ ] Отсутствие кошелька активного пользователя обрабатывается как сигнал
  инварианта: 500 `wallet_invariant_violation`; ошибки не раскрывают чужой
  баланс и внутренние детали.
- [ ] Успешный ответ и ошибки имеют `Cache-Control: no-store`; все ошибки
  соответствуют `ApiErrorResponse` с `code`, `message`, `details` и `requestId`.
- [x] Owner OpenAPI-контракт Coins и Gateway `$ref` согласованы; Gateway
  документирует публичный `503 coins_unavailable`.
- [ ] Локальная Postman-коллекция содержит авторизованный запрос баланса через
  Gateway и проверки согласованного успешного ответа.
- [ ] Maven verify затронутых модулей, Spotless/SpotBugs, Markdown-ссылки и
  git diff --check проходят.
- [ ] Результаты и ограничения записаны; недостающая среда фиксируется как
  непроверенный критерий, а не как успех.

T001–T006 выполнены; сквозная проверка подтверждена локально. Одноразовый
контейнерный e2e заблокирован `ACCOUNTS_UNAVAILABLE` и зафиксирован как ограничение.
