# Шаблоны окружения VPS

Эти файлы показывают структуру закрытых env-файлов для dev-сервера. Они не
содержат настоящих паролей, токенов или приватных ключей.

После подготовки VPS владелец создаёт файлы с такими же именами, но без
`.example`, только на сервере:

```text
/opt/fitness-platform/secrets/gateway.env
/opt/fitness-platform/secrets/identity.env
...
```

Docker Compose монтирует каждый файл в соответствующий контейнер по пути
`/run/fitness-platform/secrets/<service>.env`. Его загружает
`application-dev.yaml` сервиса.

mTLS-сертификаты и JWT-ключи создаются на VPS скриптом
`deployment/scripts/generate-dev-crypto.sh` в `/opt/fitness-platform/crypto`.
CA private key не монтируется в контейнеры.

PostgreSQL работает в Ubuntu, не в Docker. Compose добавляет внутреннее имя
`host.docker.internal`, поэтому контейнеры обращаются к
`host.docker.internal:5432`, а не к своему `localhost`.
