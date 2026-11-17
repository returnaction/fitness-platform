# Challenge Service

Каркас `challenge-service`: настроены JPA, Liquibase, PostgreSQL, валидация,
MapStruct, gRPC, Kafka и Testcontainers. Бизнес-логика, HTTP API и таблицы ещё
не реализованы. Доступны только служебные endpoints Actuator; остальные
HTTP-запросы закрыты настройкой безопасности.

Откройте корневой `pom.xml` в IntelliJ и запустите `ChallengeServiceApplication`.
Локальный профиль читает `.env`. Создайте его из `.env.example` и укажите
`SERVER_PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`,
`DATABASE_POOL_MIN_IDLE` и `DATABASE_POOL_MAX_SIZE`. Шаблон во время запуска не
загружается. В примере указан порт 8083; не запускайте сервис с открытым
доступом в интернет.

Тесты с `@SpringBootTest` используют PostgreSQL через Testcontainers. Без
работающего Docker они пропускаются по правилу `disabledWithoutDocker`.

## Docker

Команда сборки выполняется из корня проекта:

```bash
docker build -f services/challenge-service/Dockerfile -t fitness-template-challenge:local .
```

При запуске контейнера нужно явно передать `SERVER_PORT`. Например:

```bash
docker run --rm -e SERVER_PORT=8083 -p 8083:8083 fitness-template-challenge:local
```

`EXPOSE` в Dockerfile лишь документирует порт; переменную он не задаёт.
