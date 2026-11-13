# Gateway HTTP v1

Владелец входной маршрутизации — Gateway. Владелец тел, успешных ответов,
бизнес-ошибок, транзакций и идемпотентности auth — Identity;
см. [контракт сессий](../system-design/services/identity/31-identity-login-sessions.md)
и [регистрацию](../system-design/services/identity/30-identity-registration.md).

| Метод и путь | Авторизация Gateway | Upstream |
| --- | --- | --- |
| POST /api/v1/auth/register | Без access JWT; Idempotency-Key проверяет Identity | Тот же путь Identity |
| POST /api/v1/auth/login | Без access JWT; пароль проверяет Identity | Тот же путь Identity |
| POST /api/v1/auth/refresh | Без access JWT; refresh проверяет Identity | Тот же путь Identity |
| POST /api/v1/auth/logout | Без access JWT; refresh проверяет Identity | Тот же путь Identity |
| POST /api/v1/auth/logout-all | ES256 JWT + CheckSession ACTIVE | Тот же путь Identity |
| GET /api/v1/accounts/me | ES256 JWT + CheckSession ACTIVE | Тот же путь Accounts |
| PUT /api/v1/accounts/me | ES256 JWT + CheckSession ACTIVE | Тот же путь Accounts |

Пример: `POST /api/v1/auth/login`, `Content-Type: application/json`,
`{"email":"test@example.com","password":"<password>"}`. Для logout-all:
`Authorization: Bearer <accessToken>`, без тела. DTO и ответы Identity сохраняются.
Query string не поддерживается (400). Неизвестный путь — 404, другой метод
известного пути — 405 с Allow разрешённых методов (POST для auth; GET, PUT для /accounts/me). Recovery, JWKS, actuator и gRPC наружу
не маршрутизируются. Management Gateway доступен отдельно на loopback.

Gateway передаёт только Content-Type, Accept, Authorization, Idempotency-Key,
сгенерированный Gateway X-Request-Id и X-Forwarded-For из TCP peer.
Gateway всегда создаёт новый UUID; клиентский X-Request-Id игнорируется, даже корректный. Клиентские user/role,
Cookie, Forwarded и X-Forwarded-* не являются доверенным контекстом.
Identity доверяет X-Forwarded-For только от настроенного IP Gateway.

Accounts владеет формой, ответами и бизнес-ошибками собственного профиля:
[OpenAPI](openapi/accounts-profile-v1.yaml). Gateway пересылает исходный Bearer,
метод, путь, тело и request_id, возвращает статус/тело и no-store. Поля профиля
Gateway не валидирует. Маршрутизация и GET/PUT проверены сквозным сценарием Spec 014 T009.

## Проверки и отказы

JWT: ES256/P-256, доверенные issuer/audience, обязательные UUID sub/sid/jti,
iat/exp, срок не более 900 секунд. Тип roles проверяется по контракту 005;
отсутствующие/неизвестные роли не дают полномочий. Logout-all требует
аутентификации, но не определённой роли; Gateway не назначает роли сам.
JWKS загружается при старте из конфигурации; до готовности API возвращает 503.
После успешной загрузки ключи не обновляются до перезапуска Gateway;
неизвестный kid даёт 401 без сетевого запроса. Закрытые ключи JWKS запрещены.
Подробности локального запуска — в [README Gateway](../services/gateway-service/README.md).

CheckSession выполняется каждый раз, без кеша и без автоматических повторов,
по mTLS с URI SAN `spiffe://fitness.local/gateway-service`; deadline — остаток общего бюджета запроса Gateway (3s).
Соединение инициируется при запуске; отсутствие Identity не отключает защиту.
INVALID — 401 invalid_access_token; сбой/неизвестный ответ — 503 identity_unavailable.
Проверка до подтверждения logout может разрешить уже начатый запрос.

Общий deadline Gateway — 3s, включая CheckSession, HTTP upstream и передачу ответа.
При deadline HTTP-вызов отменяется; до начала ответа возвращается 504,
после начала — соединение закрывается. Регистрация при необходимости повторяется
клиентом с тем же Idempotency-Key. Автоматических повторов Gateway нет.
Прежние проектные значения rate limit, размера тела и HTTP timeout 15s
не являются описанием текущей реализации; актуальная конфигурация — в
[application.yaml](../services/gateway-service/src/main/resources/application.yaml).

HTTP transport failure — 502 upstream_unavailable, timeout — 504 upstream_timeout.
Ошибки самого Gateway имеют `{"error":{"code":"…","message":"…",
"details":[],"requestId":"<uuid>"}}`, Cache-Control: no-store и X-Request-Id.
401 содержит WWW-Authenticate: Bearer. Непредвиденная ошибка — 500 internal_error
без исходного исключения. Изменяющие запросы, включая refresh, не повторяются.
Общие коды используют сообщения Identity без изменений:

| Код | message |
| --- | --- |
| invalid_request | Check the request format. |
| invalid_access_token | Authentication required. |
| method_not_allowed | HTTP method is not supported. |
| rate_limited | Try again later. |
| identity_unavailable | Identity service is unavailable. |
| internal_error | Unexpected server error. |

Для собственных ошибок маршрутизации/транспорта Gateway (`not_found`,
`payload_too_large`, `upstream_unavailable`, `upstream_timeout`) сообщение —
`Request could not be completed.`. `details` ошибок Gateway — пустой массив.

Ответ Identity сохраняется, включая 403 и 503. Redirect автоматически не выполняется.

Только мобильный клиент; разрешающие CORS-заголовки не выдаются. При временном
503 будущий клиент сохраняет токены. Его переключение в эту поставку не входит.

## Граница локального доверия

Identity local слушает только 127.0.0.1; Gateway upstream — этот адрес.
Локальные процессы пользователя являются доверенной средой: запрета обхода
для вредоносного процесса на том же компьютере loopback не даёт. С других
сетевых интерфейсов прямой HTTP Identity должен быть недоступен. Публичный
запуск здесь не подготавливается и его защита не заявляется. По решению Spec 013
обязательного HTTPS для Identity/JWKS в профиле production нет; HTTP разрешён.
