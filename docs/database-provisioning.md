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

## Accounts Service

### 1. Создать роль и базу

Выполнить команды от имени администратора PostgreSQL один раз в каждой новой
среде, включая локальную среду разработчика и сервер. Значение пароля в Git и
документацию не попадает.

```sql
CREATE ROLE fitness_accounts_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';

CREATE DATABASE fitness_accounts
  OWNER fitness_accounts_app
  ENCODING 'UTF8'
  TEMPLATE template0;

REVOKE ALL ON DATABASE fitness_accounts FROM PUBLIC;
```

Подключиться к `fitness_accounts` как администратор и выполнить:

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_accounts_app;
```

### 2. Передать параметры сервису

Локальный `.env` модуля `accounts-service` содержит:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_accounts
DATABASE_USERNAME=fitness_accounts_app
DATABASE_PASSWORD=<пароль-роли>
SERVER_PORT=8081
```

Этот файл создаётся из `.env.example`, остаётся игнорируемым Git и хранит
реальный пароль только в локальной среде. На сервере те же переменные передаёт
защищённая конфигурация процесса.

### 3. Первый запуск и проверка

Liquibase Accounts создаст таблицы своей схемы и служебные
`databasechangelog`/`databasechangeloglock` при первом запуске сервиса. До
появления миграции список таблиц `fitness_accounts.public` остаётся пустым.

```sql
SELECT datname FROM pg_database WHERE datname = 'fitness_accounts';

SELECT rolname, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_accounts_app';

SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public';
```

## Coins Service

### 1. Создать роль и базу

Подключиться SQL-редактором от имени администратора PostgreSQL, например
`postgres`. Для новой локальной или серверной среды выполнить команды ниже.
Пароль заменить на собственный уникальный пароль; не записывать его в Git.

```sql
CREATE ROLE fitness_coins_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';
```

`CREATE DATABASE` выполнить отдельной командой вне транзакции. Если редактор
использует ручной commit, включить auto-commit для этой команды:

```sql
CREATE DATABASE fitness_coins
  OWNER fitness_coins_app
  ENCODING 'UTF8'
  TEMPLATE template0;
```

```sql
REVOKE ALL ON DATABASE fitness_coins FROM PUBLIC;
```

Затем переключить соединение SQL-редактора именно на базу `fitness_coins`,
оставив пользователя-администратора, и выполнить:

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_coins_app;
```

### 2. Проверить базу и роль

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_coins';

SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_coins_app';

SELECT has_schema_privilege('fitness_coins_app', 'public', 'USAGE') AS can_use,
       has_schema_privilege('fitness_coins_app', 'public', 'CREATE') AS can_create;
```

Последний запрос выполнять при подключении к `fitness_coins`. Ожидается
владелец `fitness_coins_app`, `rolcanlogin = true`, три административных
права `false`, а оба права на схему — `true`.

### 3. Передать параметры и запустить сервис

Скопировать `services/coins-service/.env.example` в `services/coins-service/.env`
и указать в `DATABASE_PASSWORD` пароль роли. Файл `.env` игнорируется Git.
В нём также явно указаны URL базы, имя роли, размеры пула и порт приложения.
На сервере эти значения передаются через защищённую конфигурацию процесса.

При первом запуске Coins Liquibase создаёт `coin_wallets` и служебные таблицы
`databasechangelog` и `databasechangeloglock` внутри уже созданной базы. Проверка
доступности: `http://localhost:8082/actuator/health`.

## Правило для следующих сервисов

Каждый сервис получает собственные базу и роль. Схема каждой базы создаётся
Liquibase-миграциями сервиса-владельца; другой сервис не читает и не изменяет
эти таблицы напрямую.

Резервное копирование и восстановление PostgreSQL описаны в
[плане резервного копирования](../system-design/architecture/recovery/56-postgresql-backup-plan.md).
