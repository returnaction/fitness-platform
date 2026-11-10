# ExerciseCatalog Service

Заготовка будущего сервиса `exercise-catalog-service`. Бизнес-логика, API и база данных
ещё не реализованы. Доступны только служебные endpoints Actuator; остальные HTTP-запросы закрыты настройкой безопасности.

Откройте корневой `pom.xml` в IntelliJ и запустите `ExerciseCatalogServiceApplication`.
Локальный профиль читает `.env`. Создайте его из `.env.example` и укажите локальные значения; шаблон во время запуска не загружается.
В примере указан порт 8086; чтобы изменить его, создайте `.env` в папке сервиса
и укажите `SERVER_PORT`. Не запускайте сервис с открытым доступом в интернет.

## Docker

Команда сборки выполняется из корня проекта:

```bash
docker build -f services/exercise-catalog-service/Dockerfile -t fitness-template-exercise-catalog:local .
```

При запуске контейнера нужно явно передать `SERVER_PORT`. Например:

```bash
docker run --rm -e SERVER_PORT=8086 -p 8086:8086 fitness-template-exercise-catalog:local
```

`EXPOSE` в Dockerfile лишь документирует порт; переменную он не задаёт.
