# Spec 017: backend API баланса Coins через HTTP

> **Статус:** реализация завершена; дополнительные проверки после ревью открыты в tasks.md
> **Ветка:** `feature/48-017-coins-wallet-realtime`
> **Дата:** 2026-11-14
> **Документы:** [план](plan.md), [задачи](tasks.md), [вопросы](questions.md), [проверка](verification.md).

## Цель

Предоставить защищённый HTTP API актуального баланса через маршрут
`gateway-service → coins-service`.

## Подтверждённое состояние на 2026-11-16

Реализация выполнена; после merge main проведены ревью, исправление обработки
обрыва соединения и локальные HTTP-проверки через Gateway.

| Компонент | Реализовано |
| --- | --- |
| [coins-service](../../services/coins-service/) | GET баланса, извлечение claims в SecurityContext, controller/service/repository, единый формат ошибок |
| [gateway-service](../../services/gateway-service/) | Защищённый маршрут COINS, проверка JWT и сессии, пересылка Authorization и X-Request-Id |
| [contracts](../../contracts/) | Owner OpenAPI Coins и ссылка на его path из Gateway |

Coins владеет балансом и остаётся источником истины. WalletRepository читает
кошелёк по UUID пользователя; балансы представлены long / BIGINT NOT NULL.
Coins извлекает claims из токена, уже проверенного Gateway, и очищает
SecurityContext после запроса. Ошибки фильтра передаются через
SecurityExceptionHandler в общий HTTP-обработчик.

Подтверждены 200, 401, отсутствие кошелька и 503 при обрыве существующего
соединения с БД, затем восстановление 200. Длительная недоступность БД и
согласование ожидания пула с дедлайном Gateway остаются отложенной проверкой.
Даты, среда и границы доказательств приведены в [verification.md](verification.md).

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

- [x] GET через Gateway с действующей сессией возвращает 200 и баланс владельца;
  без сессии — 401; чужой баланс недоступен.
- [x] Отсутствие кошелька активного пользователя обрабатывается как сигнал
  инварианта: 500 `wallet_invariant_violation`; ошибки не раскрывают чужой
  баланс и внутренние детали.
- [x] Успешный ответ и ошибки имеют `Cache-Control: no-store`; все ошибки
  соответствуют `ApiErrorResponse` с `code`, `message`, `details` и `requestId`.
- [x] Owner OpenAPI-контракт Coins и Gateway `$ref` согласованы; Gateway
  документирует публичный `503 coins_unavailable`.
- [x] Локальная Postman-коллекция содержит авторизованный запрос баланса через
  Gateway и проверки согласованного успешного ответа.
- [ ] Итоговая сверка после ревью: Maven verify / SpotBugs, актуальные
  контракты, Markdown-ссылки и git diff --check. Полный build пользователя
  прошёл до последнего исправления, после него проверен Coins verify;
  повторная проверка контрактов остаётся открытой. Spotless отключён
  согласно инженерным стандартам.
- [x] Результаты и ограничения записаны; недостающая среда фиксируется как
  непроверенный критерий, а не как успех.

Исходные T001–T006 завершены; дополнительные пункты после ревью перечислены
в [tasks.md](tasks.md). Старый контейнерный e2e не прошёл, его скрипт удалён
по решению пользователя. Это историческое ограничение, а не ожидающий запуска
текущий тест. Серверное развёртывание этой ветки не проверено.
