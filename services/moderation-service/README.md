# Moderation Service

Заготовка будущего сервиса `moderation-service`. Бизнес-логика, API и база данных
ещё не реализованы. Доступны только служебные endpoints Actuator; остальные HTTP-запросы закрыты настройкой безопасности.

Откройте корневой `pom.xml` в IntelliJ и запустите `ModerationServiceApplication`.
Локальный профиль читает `.env`. Создайте его из `.env.example` и укажите локальные значения; шаблон во время запуска не загружается.
В примере указан порт 8085; чтобы изменить его, создайте `.env` в папке сервиса
и укажите `SERVER_PORT`. Не запускайте сервис с открытым доступом в интернет.

## Docker

Команда сборки выполняется из корня проекта:

```bash
docker build -f services/moderation-service/Dockerfile -t fitness-template-moderation:local .
```

При запуске контейнера нужно явно передать `SERVER_PORT`. Например:

```bash
docker run --rm -e SERVER_PORT=8085 -p 8085:8085 fitness-template-moderation:local
```

`EXPOSE` в Dockerfile лишь документирует порт; переменную он не задаёт.
