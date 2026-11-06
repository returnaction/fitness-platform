# Подготовка PostgreSQL для сервисов

Этот документ описывает действия, которые выполняются **один раз до первого
запуска** сервиса в локальной среде или на сервере. Liquibase не создаёт роль
PostgreSQL и саму базу: он создаёт и изменяет только таблицы внутри уже
доступной сервису базы.

Для каждого микросервиса создаются отдельные база и роль приложения. Нельзя
подключать Identity, Accounts и Coins одной ролью к общей базе.

## Identity Service

### 1. Создать роль и базу

Выполнить команды от имени администратора PostgreSQL (обычно роль `postgres`).
Значение в угловых скобках заменяется длинным уникальным паролем и **не
сохраняется в Git, документации или логах**.

```sql
CREATE ROLE fitness_identity_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';

CREATE DATABASE fitness_identity
  OWNER fitness_identity_app
  ENCODING 'UTF8'
  TEMPLATE template0;

REVOKE ALL ON DATABASE fitness_identity FROM PUBLIC;
```

Затем подключиться к созданной базе как администратор и ограничить права схемы:

```sql
\c fitness_identity

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_identity_app;
```

Роль приложения является владельцем своей базы, поэтому Liquibase может
создавать таблицы и свою служебную историю миграций. У неё нет прав создавать
другие базы, роли или выполнять административные действия на сервере.

### 2. Передать параметры сервису

Локально скрипт создаёт игнорируемый файл
`services/identity-service/.env`; в нём указывают:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_identity
DATABASE_USERNAME=fitness_identity_app
DATABASE_PASSWORD=<пароль-роли>
SERVER_PORT=8080
```

На сервере передаются те же четыре переменные окружения, но секрет хранится в
защищённой конфигурации процесса или средства развёртывания, а не в репозитории
и не в образе Docker. Точный механизм (например, файл окружения systemd с
правами только для администратора) будет зафиксирован вместе с первым
deployment-конфигом.

### 3. Первый запуск и проверка

При первом запуске Identity Service Liquibase создаёт таблицы приложения и
`databasechangelog`/`databasechangeloglock`. При последующих запусках он читает
`databasechangelog` и применяет только новые changeset-ы.

Проверить подключение можно так:

```powershell
Invoke-WebRequest http://localhost:8080/actuator/health
```

Ожидается HTTP `200` и JSON со `status: UP`. В журнале старта должны быть
сообщения `HikariPool ... Start completed`, `Liquibase: Update has been
successful` при первом запуске либо `Database is up to date` при повторном.

Проверка от имени администратора PostgreSQL:

```sql
\du fitness_identity_app
\l fitness_identity
\c fitness_identity
\dt
SELECT id, author, filename, dateexecuted
FROM databasechangelog
ORDER BY dateexecuted;
```

## Правило для следующих сервисов

Перед первым запуском Accounts и Coins повторяется тот же шаблон с собственными
именами: `fitness_accounts` / `fitness_accounts_app` и `fitness_coins` /
`fitness_coins_app`. Схема каждой базы создаётся исключительно Liquibase-
миграциями сервиса-владельца; другой сервис не читает и не изменяет эти таблицы
напрямую.

Резервное копирование и восстановление PostgreSQL описаны в
[плане резервного копирования](../system-design/architecture/recovery/56-postgresql-backup-plan.md).
