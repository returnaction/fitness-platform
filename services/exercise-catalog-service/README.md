# ExerciseCatalog Service

Сервис `exercise-catalog-service` подключён к собственной PostgreSQL-базе
`fitness_exercise_catalog`; схемой управляет Liquibase. Таблицы каталога, бизнес-логика
и API ещё не реализованы. Доступны только служебные endpoints Actuator; остальные
HTTP-запросы закрыты настройкой безопасности.

Перед первым запуском создайте базу и роль приложения по разделу «Exercise Catalog
Service» в [docs/database-setup.md](../../docs/database-setup.md).

Откройте корневой `pom.xml` в IntelliJ и запустите `ExerciseCatalogServiceApplication`.
Локальный профиль читает `.env`. Создайте его из `.env.example` в папке сервиса и укажите
локальные значения, включая пароль роли в `DATABASE_PASSWORD`; шаблон во время запуска
не загружается, а `.env` не добавляется в Git. Все переменные обязательны: значений по
умолчанию нет, и без них сервис не стартует. В примере указан порт 8086.
Не запускайте сервис с открытым доступом в интернет.

При старте Liquibase применяет миграции из `db/changelog/db.changelog-master.yaml`.
Проверка: `http://localhost:8086/actuator/health` отвечает `200` и `{"status":"UP"}`,
а в базе есть таблицы `databasechangelog` и `databasechangeloglock`.

## Docker

Команда сборки выполняется из корня проекта:

```bash
docker build -f services/exercise-catalog-service/Dockerfile -t fitness-template-exercise-catalog:local .
```

При запуске контейнера нужно явно передать `SERVER_PORT` и все переменные `DATABASE_*`
из `.env.example`. Например:

```bash
docker run --rm -p 8086:8086 \
  -e SERVER_PORT=8086 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/fitness_exercise_catalog \
  -e DATABASE_USERNAME=fitness_exercise_catalog_app \
  -e DATABASE_PASSWORD=<пароль-роли> \
  -e DATABASE_POOL_MIN_IDLE=2 \
  -e DATABASE_POOL_MAX_SIZE=10 \
  fitness-template-exercise-catalog:local
```

Внутри контейнера `localhost` означает сам контейнер, а не компьютер с PostgreSQL;
`host.docker.internal` работает в Docker Desktop. Локальный PostgreSQL должен принимать
подключения из сети Docker. Не сохраняйте настоящий пароль в истории команд и скриптах.

`EXPOSE` в Dockerfile лишь документирует порт; переменную он не задаёт.
