# VideoProof Service

Заготовка будущего сервиса `video-proof-service`. Бизнес-логика, API и база данных
ещё не реализованы. Доступны только служебные endpoints Actuator; остальные HTTP-запросы закрыты настройкой безопасности.

Откройте корневой `pom.xml` в IntelliJ и запустите `VideoProofServiceApplication`.
Локальный профиль автоматически читает `.env.example` и при наличии `.env`.
В примере указан порт 8084; чтобы изменить его, создайте `.env` в папке сервиса
и укажите `SERVER_PORT`. Не запускайте сервис с открытым доступом в интернет.

## Docker

Команда сборки выполняется из корня проекта:

```bash
docker build -f services/video-proof-service/Dockerfile -t fitness-template-video-proof:local .
```

При запуске контейнера нужно явно передать `SERVER_PORT`. Например:

```bash
docker run --rm -e SERVER_PORT=8084 -p 8084:8084 fitness-template-video-proof:local
```

`EXPOSE` в Dockerfile лишь документирует порт; переменную он не задаёт.
