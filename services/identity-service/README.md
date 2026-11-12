# Identity: локальный HTTP

По решению пользователя HTTP mTLS из T002 отменён. Identity принимает обычный
HTTP на 127.0.0.1:8080; сертификат HTTP-клиента не требуется. Gateway использует
`http://127.0.0.1:8080` и получает JWKS по `/.well-known/jwks.json`.

Пять auth POST и GET JWKS доступны на API listener. Это allowlist операций,
а не проверка личности вызывающего сервиса. Любой локальный процесс может
обратиться к ним. Для logout-all сохранены полная проверка JWT и текущие
бизнес-проверки Identity; pre-authentication не внедрён.

Management работает отдельно по HTTP на 127.0.0.1:9080 (IDENTITY_MANAGEMENT_PORT).
На нём разрешены предусмотренные GET health/readiness/liveness/info/prometheus,
пользовательские операции запрещены. На API-порту management запрещён.
Recovery закрыт; его код и JWT-проверки сохранены. Rate limiter удалён.

Существующий mTLS gRPC CheckSession и вызовов Accounts/Coins не меняется:
его сертификаты и настройки нужны по-прежнему. Переменные IDENTITY_HTTP_CERTIFICATE,
IDENTITY_HTTP_PRIVATE_KEY и IDENTITY_HTTP_TRUST_CERTIFICATE больше не нужны.

Проверочный запуск из корня без миграций пользовательской БД:

```powershell
java -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar --spring.liquibase.enabled=false
curl.exe http://127.0.0.1:9080/actuator/health/readiness
curl.exe http://127.0.0.1:8080/.well-known/jwks.json
```

Нужна уже подготовленная локальная БД. Gateway также запускается по обычному
HTTP. Внешняя серверная изоляция Docker в этой работе не настраивается.
