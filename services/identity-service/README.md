# Identity: локальный доступ после T002 Spec 013

Пользовательский listener — HTTPS с обязательным клиентским сертификатом.
Локальный профиль привязывает HTTP к 127.0.0.1. Пять POST auth-операций и GET
`/.well-known/jwks.json` разрешены только TLS caller с URI SAN
`spiffe://fitness.local/gateway-service`. Сертификат другого сервиса от той же
CA прав не даёт. Проверка caller не создаёт пользовательский Authentication.
JWT для logout-all пока проверяется повторно: pre-authentication относится к T005.

## Настройки

В `.env` Identity нужны параметры из `.env.example`:

- `IDENTITY_HTTP_CERTIFICATE` — PEM сертификат сервера с промежуточной цепочкой;
- `IDENTITY_HTTP_PRIVATE_KEY` — соответствующий приватный ключ;
- `IDENTITY_HTTP_TRUST_CERTIFICATE` — доверенная CA для клиентских сертификатов;
- `IDENTITY_MANAGEMENT_PORT` — отдельный management-порт (локальный пример 9080).

Существующие gRPC-credentials остаются без изменений. У локального сертификата
сервера DNS SAN `localhost`; HTTPS-клиент должен обращаться к `https://localhost:8080`
и проверять цепочку и имя. Не отключать проверку имени. Клиент предоставляет
свою полную цепочку и ключ. Самоподписанный/недоверенный клиент отклоняется на TLS,
доверенный сертификат с чужой identity получает HTTP 403 `forbidden` без деталей.

Management — отдельный HTTP listener на 127.0.0.1, без mTLS, доступ контролируется
локальным размещением/доступом к ОС. Разрешены только GET health, health/liveness,
health/readiness, info и prometheus. На нём пользовательские операции запрещены;
на API-порту management запрещён. Recovery закрыт для всех HTTP callers,
включая Gateway. Код recovery и его JWT/БД-проверки сохранены для будущего доступа.

`forward-headers-strategy: none` сохраняет реальный адрес TLS-соединения.
X-Forwarded-For не используется для принятия решений о доступе.
По решению пользователя rate limiter Identity и его настройки удалены;
Identity больше не выдаёт 429 rate_limited из-за частоты auth-запросов.

## Локальный запуск для проверки без миграций

Из корня после сборки:

```powershell
java -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar --spring.liquibase.enabled=false
curl.exe http://127.0.0.1:9080/actuator/health/readiness
```

Требуется уже подготовленная локальная БД Identity. Команда не применяет миграции.
Если схема не соответствует коду, запуск может завершиться ошибкой; не обходить
валидацию схемы. Для регистрации также нужны локальные Accounts и Coins.

После T002 старые HTTP-клиенты Gateway ещё не подключены к HTTPS/mTLS Identity.
Это ожидаемая промежуточная граница: T003 настраивает оба клиента Gateway
(forwarding/JWKS), T004 проверяет сквозной защищённый путь. Новые публичные маршруты
не добавляются. Изменение HTTP URL не меняет строку JWT issuer автоматически.

Менять сертификаты с перезапуском; перерыв допустим. Не хранить приватные ключи
в Git. Конфигурация контейнеров/проверка внешнего firewall относится к T008;
локальные результаты не доказывают защиту серверного развёртывания.
