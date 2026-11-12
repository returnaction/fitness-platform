# Проверка T006 — 2026-11-12

## Среда и запуск

Ветка 39-gateway-refactor. Проверены реальные локальные Java-процессы Gateway,
Identity, Accounts и Coins с существующими .env, локальными PostgreSQL и
сертификатами внутренних gRPC. HTTP остаётся без HTTPS. Новый стенд и контейнеры
не создавались. Перед стартом порты проверены на отсутствие занятых listeners.

Актуальные jar собраны командой:

```powershell
./mvnw.cmd -pl services/identity-service,services/accounts-service,services/coins-service,services/gateway-service -am '-DskipTests' package
```

Каждый сервис запускался из корня репозитория:

```powershell
java -jar services/<service>-service/target/<service>-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --spring.liquibase.enabled=false --spring.jpa.hibernate.ddl-auto=validate
```

Accounts HTTP 8081, Coins HTTP 8082, Identity HTTP 8080 / management 9080,
Gateway HTTP 8088 / management 9088. Сценарий отправлял HTTP-запросы только
на 127.0.0.1. Для проверок создан один новый пользователь через API регистрации;
существующие учётные записи не изменялись. Тестовая запись остаётся в локальной БД,
все её созданные сессии отозваны итоговым logout-all. Пароль генерировался в памяти,
не выводился и не сохранялся. Миграции пользовательских БД отключены.

## Результаты — 46 успешных проверок

| Проверка | Результат |
| --- | --- |
| Readiness всех четырёх сервисов | 200 |
| JWKS Identity по HTTP и readiness Gateway после запуска | 200; Gateway загрузил ключи |
| Liveness и Prometheus на management Identity/Gateway | 200 |
| Management на API Identity / пользовательский API на management Identity | 403 / 403 |
| Management на публичном API Gateway | 404 |
| Административный recovery Identity | 403 |
| Регистрация через Gateway | 201, пользователь ACTIVE; подготовка профиля Accounts и кошелька Coins прошла через существующие gRPC-вызовы |
| Register/login/refresh/logout без Authorization, с корректным и с повреждённым bearer | 201/200/200/204; публичные операции не требуют access-токен |
| Logout и затем logout-all с токеном отозванной сессии | 204, затем Gateway 401 через CheckSession |
| Logout-all без токена, с повреждённым JWT и изменённой подписью | Gateway 401 |
| Refresh и повторное использование старого refresh | 200, затем 401 |
| Logout-all после обнаружения refresh replay | Gateway 401: сессия отозвана |
| Logout-all с действующим access через Gateway | 204 |
| Токен второй сессии и её refresh после logout-all | 401 / 401 |
| Прямая повторная доставка уже принятого logout-all в Identity | 204, в соответствии с локальным допущением доверия |
| Identity logout-all без bearer после предыдущего успешного запроса | 401 |
| Итоговый readiness Gateway | 200 |

HTTP 204 через Gateway проверяет весь путь JWT → mTLS CheckSession → HTTP Identity
→ UUID в SecurityContext → транзакционный отзыв. Регистрация до ACTIVE подтверждает
успешную подготовку профиля и кошелька; отдельные SQL-проверки чужих таблиц не делались.
Пароли/access/refresh не обнаружены в логах запущенных процессов. Все четыре
запущенных проверкой процесса остановлены в finally; чужие процессы не останавливались.

## Ранее выполненные автоматические проверки

T005: полный verify Identity — 134 теста, 0 ошибок, 2 opt-in пропуска;
Spotless/SpotBugs прошли. Включены повреждённые sub/токены, отсутствие требований
ролей, очистка и параллельная изоляция SecurityContext, истечение токена во время
ожидания блокировки, отозванная исходная сессия и SUSPENDED, повторные команды,
гонки с login/refresh/регистрацией, откат отзыва при ошибке БД.

После удаления ProductionConfiguration: 43 теста Gateway прошли без ошибок;
проверки JWT включают отказ недействительным токенам. SpotBugs прошёл отдельно.

## Ограничения и оставшаяся работа

T006 завершён совокупностью автоматических и настоящих локальных проверок.
T007 остаётся открытым: полный Gateway verify пока блокируется ранее выявленными
замечаниями Spotless в AccessTokenProperties, SessionVerificationProperties,
UpstreamProperties и GatewayExceptionHandler. Их исправление в этот прогон не входило.

Прямой локальный HTTP caller Identity не аутентифицируется; это согласованное
допущение, а не защита от подделанного локального запроса. Серверная изоляция,
Docker-развёртывание и мобильное приложение не проверялись. Сценарии гонок
проверены PostgreSQL integration-тестами T005, не искусственными изменениями
состояния пользовательской БД в этом запуске. Коммит не создавался.
