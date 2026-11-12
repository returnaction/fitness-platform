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

Пример: `POST /api/v1/auth/login`, `Content-Type: application/json`,
`{"email":"test@example.com","password":"<password>"}`. Для logout-all:
`Authorization: Bearer <access_token>`, без тела. DTO и ответы Identity сохраняются.
Query string не поддерживается (400). Неизвестный путь — 404, другой метод
известного пути — 405 с `Allow: POST`. Recovery, JWKS, actuator и gRPC наружу
не маршрутизируются. Management Gateway доступен отдельно на loopback.

Gateway передаёт только Content-Type, Accept, Authorization, Idempotency-Key,
канонический X-Request-Id и X-Forwarded-For из TCP peer. Клиентские user/role,
Cookie, Forwarded и X-Forwarded-* не являются доверенным контекстом.
Identity доверяет X-Forwarded-For только от настроенного IP Gateway.

## Проверки и отказы

JWT: ES256/P-256, доверенные issuer/audience, обязательные UUID sub/sid/jti,
iat/exp, срок не более 900 секунд. Тип roles проверяется по контракту 005;
отсутствующие/неизвестные роли не дают полномочий. Logout-all требует
аутентификации, но не определённой роли; Gateway не назначает роли сам. JWKS берётся только
из конфигурации: кеш 300 секунд, обновление не чаще раза в 5 секунд (включая
неизвестный kid и ошибки). Нет пригодного ключа из-за недоступности — 503;
подтверждённо неверный токен — 401. Закрытые ключи JWKS запрещены.

CheckSession выполняется каждый раз, без кеша и без автоматических повторов,
по mTLS с URI SAN `spiffe://fitness.local/gateway-service`; deadline 2500 мс.
Соединение инициируется при запуске; отсутствие Identity не отключает защиту.
INVALID — 401 invalid_access_token; сбой/неизвестный ответ — 503 identity_unavailable.
Проверка до подтверждения logout может разрешить уже начатый запрос.

Лимиты v1 (конфигурируемые): тело 16384 байта, HTTP headers 16 KiB,
60 запросов за фиксированное окно 60 секунд на TCP IP, максимум 10000 IP.
При заполнении таблицы новые IP получают 429, а не вытесняют действующие лимиты.
429 содержит Retry-After 60; тело сверх лимита — 413 payload_too_large.
HTTP connect/read timeout 15000 мс: больше 12000 мс ожидания регистрации Identity;
соединения Tomcat 256, workers 64. При изменении ожидания Identity согласовать
таймаут Gateway, чтобы не обрывать нормальный ответ регистрации.
Локальные лимиты рассчитаны на один экземпляр, не являются защитой от DDoS.

HTTP transport failure — 502 upstream_unavailable, timeout — 504 upstream_timeout.
Ошибки самого Gateway имеют `{"error":{"code":"…","message":"…",
"details":[],"request_id":"<uuid>"}}`, Cache-Control: no-store и X-Request-Id.
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
