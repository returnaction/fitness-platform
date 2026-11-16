# План реализации: подготовка односерверной dev-выкладки

> **Спецификация:** [spec.md](spec.md)
> **Статус:** implemented
> **Решения:** [decisions.md](decisions.md)

## Краткое решение

VPS остаётся одним dev-сервером. PostgreSQL 17 устанавливается напрямую в
Ubuntu. Выпускаемые Java-сервисы и будущая Kafka работают в Docker Compose.
GitHub Actions тестирует выпущенный сервис, публикует его exact-version image
в public GHCR и по SSH обновляет только этот контейнер.

## Этапы

1. **Репозиторий и release-модель.** Разделить версии root POM,
   `grpc-contracts` и сервисов; убрать зависимость Dockerfile от
   `0.1.0-SNAPSHOT`; проверить dev-конфигурацию и `.env.example`.
2. **Server Compose и секреты.** Добавить versioned Compose без секретов,
   server secret layout, Bash-генератор mTLS/JWT и dump/log policy.
3. **GitHub delivery.** Настроить GitHub Actions, public GHCR, exact image
   tags, service-scoped tests, SSH-доставку, health-check и cleanup.
4. **Чистый VPS.** После отдельного разрешения переустановить Ubuntu и
   подготовить SSH, UFW, Docker, PostgreSQL, DBeaver access, directories и
   GHCR login.
5. **Первый выпуск.** Вручную подготовить DB roles/databases, затем выпустить
   Accounts, Coins, Identity и Gateway, проверяя каждый шаг.

## Затрагиваемые границы

| Область | Изменение | Владелец |
| --- | --- | --- |
| Root Maven / service POM | Независимые release-версии | Репозиторий |
| `contracts/grpc-contracts` | Отдельная версия и явные зависимости | Контракты |
| Dockerfile / Compose | Воспроизводимый image и runtime-конфигурация | Platform |
| GitHub Actions / GHCR | Сборка, публикация и доставка | Platform |
| Ubuntu / PostgreSQL | Runtime и сервисные базы | Platform / service owner |
| Identity | JWT keys, mTLS client/server files | Identity |

## Данные и миграции

- PostgreSQL instance один, но каждая service-owned БД и роль отдельные.
- Административная роль используется владельцем через защищённое подключение.
- Для первой БД создаются вручную по `docs/database-setup.md`; Liquibase
  создаёт и обновляет только таблицы сервиса-владельца.
- Автоматический rollback схемы не реализуется.

## Контракты и взаимодействия

| Направление | Тип | Правило |
| --- | --- | --- |
| Телефон → Gateway | HTTP, `:8088` | Единственный внешний порт dev-сервера. |
| Gateway → сервисы | Внутренняя Docker-сеть | Внутренние порты не публикуются. |
| Сервисы → PostgreSQL | Внутренний путь Docker → host | Не `localhost` контейнера, без внешнего `5432`. |
| GitHub Actions → VPS | SSH | Отдельный ключ, exact release only. |
| DBeaver → PostgreSQL | SSH-tunnel | Административный доступ без публикации БД в интернет. |

## Надёжность и безопасность

- SSH: ключи, no root login, no password login, UFW limit.
- UFW: только `22` и `8088`; нет публичного PostgreSQL.
- Runtime secrets: отдельные закрытые файлы сервисов вне Git/GHCR;
  доступ ограничивается правами ОС.
- mTLS/JWT: генерируются на VPS, монтируются read-only; mTLS срок 90 дней.
- Container: UID `10001`, `unless-stopped`, Docker log rotation, health-check.
- OOM: heap dump и JVM error files сохраняются в закрытую persistent directory.

## Проверка

- Maven Wrapper tests/build для каждого выпускаемого сервиса и общих модулей.
- Проверка `docker compose config`, image tags и отсутствия секретов в Git.
- Проверка SSH, UFW, DBeaver tunnel, SCRAM authentication и запрета public
  `5432` после подготовки VPS.
- Проверка Liquibase changelog, health-check и HTTP-сценария через Gateway.

## Риски и решения

| Риск | Решение |
| --- | --- |
| Недостаточно RAM | Лимиты выбрать после первого измерения; увеличить ресурсы только при подтверждённой нехватке. |
| Ошибка migration/health | Не откатывать автоматически; сохранить логи и разбирать вручную. |
| Заполнение диска | Image cleanup после успеха, Docker/system log rotation, dump files удаляются вручную после анализа. |
| Потеря dev VPS | Бэкапы намеренно не входят в текущую спецификацию. |
