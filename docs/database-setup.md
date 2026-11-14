# Подготовка PostgreSQL для сервисов

Этот документ описывает действия, которые выполняются один раз до первого
запуска сервиса в локальной среде или на сервере. Работа выполняется в
**DBeaver** через подключение PostgreSQL.

Для каждого микро-сервиса создаются:

- отдельная **роль приложения** — технический пользователь PostgreSQL, под
  которым запускается только этот микро-сервис;
- отдельная **база данных** — только этого сервиса;
- доступ роли только к её собственной базе и схеме `public`.


При первом локальном запуске сначала подготовьте окружение по шагу 4.1
в [«Локальном запуске»](../LOCAL-SETUP.md). Затем выполните шаги 1–5 ниже для
каждого нужного сервиса с локальной БД. Для запуска сервисов вернитесь к
шагам 4.3–4.4 в «Локальном запуске». Шаг 6 ниже используется для проверки
после запуска.

## Identity Service

**База:** `fitness_identity`
**Роль приложения:** `fitness_identity_app`
**Локальный порт:** `8080`

### 1. Создать роль приложения

В DBeaver подключитесь к базе `postgres` под администратором и включите
**Auto-commit**. Выполняйте SQL-блоки по одному: выделите весь блок, нажмите
`Ctrl+Enter` и дождитесь успешного выполнения.

Подставьте пароль для роли `fitness_identity_app` и выполните запрос.

```sql
CREATE ROLE fitness_identity_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD 'postgres';
```

### 2. Создать базу данных

В том же подключении с включённым **Auto-commit** создайте базу.

```sql
CREATE DATABASE fitness_identity
  OWNER fitness_identity_app
  ENCODING 'UTF8'
  TEMPLATE template0;
```

После создания базы отдельно уберите права `PUBLIC` на неё.

```sql
REVOKE ALL ON DATABASE fitness_identity FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver откройте SQL-редактор для базы `fitness_identity` под администратором.
Уберите права `PUBLIC` на схему `public`.

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
```

Затем отдельно выдайте права роли приложения.

```sql
GRANT USAGE, CREATE ON SCHEMA public TO fitness_identity_app;
```

### 4. Настроить сервис

1. Откройте `services/identity-service/.env`, созданный при
   [подготовке окружения](../LOCAL-SETUP.md).
2. Найдите существующую строку `DATABASE_PASSWORD=replace_with_local_password`.
3. В этой же строке замените значение после `=` на пароль роли
   `fitness_identity_app`, который задали на шаге 1. Сохраните файл.

### 5. Проверить базу и роль в DBeaver

В DBeaver оставайтесь в базе `fitness_identity`. Выполните каждый блок отдельно.

Владелец базы должен быть `fitness_identity_app`.

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_identity';
```

У роли `rolcanlogin` должен быть `true`, остальные три флага — `false`.

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_identity_app';
```

Права `can_use` и `can_create` должны быть `true`.

```sql
SELECT has_schema_privilege(
         'fitness_identity_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_identity_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

Настройте ключи и сертификаты по [инструкции по окружению](environment-configuration.md)
и запустите `identity-service` с профилем `local`. При запуске Liquibase применит
миграции к `fitness_identity`.

В PowerShell проверьте статус `UP`. В шаблоне `.env.example` для этой проверки
задан порт `9080` (`IDENTITY_MANAGEMENT_PORT`).

```powershell
Invoke-RestMethod http://localhost:9080/actuator/health
```

В DBeaver, в базе `fitness_identity`, проверьте созданные таблицы.

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

Отдельно проверьте историю миграций Liquibase.

```sql
SELECT id, author, filename, dateexecuted
FROM public.databasechangelog
ORDER BY dateexecuted;
```

## Accounts Service

**База:** `fitness_accounts`
**Роль приложения:** `fitness_accounts_app`
**Локальный порт:** `8081`

### 1. Создать роль приложения

В DBeaver подключитесь к базе `postgres` под администратором и включите
**Auto-commit**. Выполняйте SQL-блоки по одному: выделите весь блок, нажмите
`Ctrl+Enter` и дождитесь успешного выполнения.

Подставьте пароль для роли `fitness_accounts_app` и выполните запрос.

```sql
CREATE ROLE fitness_accounts_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD 'postgres';
```

### 2. Создать базу данных

В том же подключении с включённым **Auto-commit** создайте базу.

```sql
CREATE DATABASE fitness_accounts
  OWNER fitness_accounts_app
  ENCODING 'UTF8'
  TEMPLATE template0;
```

После создания базы отдельно уберите права `PUBLIC` на неё.

```sql
REVOKE ALL ON DATABASE fitness_accounts FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver откройте SQL-редактор для базы `fitness_accounts` под администратором.
Уберите права `PUBLIC` на схему `public`.

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
```

Затем отдельно выдайте права роли приложения.

```sql
GRANT USAGE, CREATE ON SCHEMA public TO fitness_accounts_app;
```

### 4. Настроить сервис

После подготовки окружения по инструкции [«Локальный запуск»](../LOCAL-SETUP.md)
откройте `services/accounts-service/.env` и проверьте указанные строки.
В `DATABASE_PASSWORD` впишите пароль роли из шага 1.

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_accounts
DATABASE_USERNAME=fitness_accounts_app
DATABASE_PASSWORD=postgres
SERVER_PORT=8081
```

### 5. Проверить базу и роль в DBeaver

В DBeaver оставайтесь в базе `fitness_accounts`. Выполните каждый блок отдельно.

Владелец базы должен быть `fitness_accounts_app`.

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_accounts';
```

У роли `rolcanlogin` должен быть `true`, остальные три флага — `false`.

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_accounts_app';
```

Права `can_use` и `can_create` должны быть `true`.

```sql
SELECT has_schema_privilege(
         'fitness_accounts_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_accounts_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

Настройте ключи и сертификаты по [инструкции по окружению](environment-configuration.md)
и запустите `accounts-service` с профилем `local`. При запуске Liquibase применит
миграции к `fitness_accounts`.

В PowerShell проверьте статус `UP`.

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

В DBeaver, в базе `fitness_accounts`, проверьте созданные таблицы.

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

Отдельно проверьте историю миграций Liquibase.

```sql
SELECT id, author, filename, dateexecuted
FROM public.databasechangelog
ORDER BY dateexecuted;
```

## Coins Service

**База:** `fitness_coins`
**Роль приложения:** `fitness_coins_app`
**Локальный порт:** `8082`

### 1. Создать роль приложения

В DBeaver подключитесь к базе `postgres` под администратором и включите
**Auto-commit**. Выполняйте SQL-блоки по одному: выделите весь блок, нажмите
`Ctrl+Enter` и дождитесь успешного выполнения.

Подставьте пароль для роли `fitness_coins_app` и выполните запрос.

```sql
CREATE ROLE fitness_coins_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD 'postgres';
```

### 2. Создать базу данных

В том же подключении с включённым **Auto-commit** создайте базу.

```sql
CREATE DATABASE fitness_coins
  OWNER fitness_coins_app
  ENCODING 'UTF8'
  TEMPLATE template0;
```

После создания базы отдельно уберите права `PUBLIC` на неё.

```sql
REVOKE ALL ON DATABASE fitness_coins FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver откройте SQL-редактор для базы `fitness_coins` под администратором.
Уберите права `PUBLIC` на схему `public`.

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
```

Затем отдельно выдайте права роли приложения.

```sql
GRANT USAGE, CREATE ON SCHEMA public TO fitness_coins_app;
```

### 4. Настроить сервис

После подготовки окружения по инструкции [«Локальный запуск»](../LOCAL-SETUP.md)
откройте `services/coins-service/.env` и проверьте указанные строки.
В `DATABASE_PASSWORD` впишите пароль роли из шага 1.

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_coins
DATABASE_USERNAME=fitness_coins_app
DATABASE_PASSWORD=postgres
SERVER_PORT=8082
```

### 5. Проверить базу и роль в DBeaver

В DBeaver оставайтесь в базе `fitness_coins`. Выполните каждый блок отдельно.

Владелец базы должен быть `fitness_coins_app`.

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_coins';
```

У роли `rolcanlogin` должен быть `true`, остальные три флага — `false`.

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_coins_app';
```

Права `can_use` и `can_create` должны быть `true`.

```sql
SELECT has_schema_privilege(
         'fitness_coins_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_coins_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

Настройте ключи и сертификаты по [инструкции по окружению](environment-configuration.md)
и запустите `coins-service` с профилем `local`. При запуске Liquibase применит
миграции к `fitness_coins`.

В PowerShell проверьте статус `UP`.

```powershell
Invoke-RestMethod http://localhost:8082/actuator/health
```

В DBeaver, в базе `fitness_coins`, проверьте созданные таблицы.

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

Отдельно проверьте историю миграций Liquibase.

```sql
SELECT id, author, filename, dateexecuted
FROM public.databasechangelog
ORDER BY dateexecuted;
```

## Exercise Catalog Service

**База:** `fitness_exercise_catalog`
**Роль приложения:** `fitness_exercise_catalog_app`
**Локальный порт:** `8086`

### 1. Создать роль приложения

В DBeaver подключитесь к базе `postgres` под администратором и включите
**Auto-commit**. Выполняйте SQL-блоки по одному: выделите весь блок, нажмите
`Ctrl+Enter` и дождитесь успешного выполнения.

Подставьте пароль для роли `fitness_exercise_catalog_app` и выполните запрос.

```sql
CREATE ROLE fitness_exercise_catalog_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD 'postgres';
```

### 2. Создать базу данных

В том же подключении с включённым **Auto-commit** создайте базу.

```sql
CREATE DATABASE fitness_exercise_catalog
  OWNER fitness_exercise_catalog_app
  ENCODING 'UTF8'
  TEMPLATE template0;
```

После создания базы отдельно уберите права `PUBLIC` на неё.

```sql
REVOKE ALL ON DATABASE fitness_exercise_catalog FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver откройте SQL-редактор для базы `fitness_exercise_catalog` под администратором.
Уберите права `PUBLIC` на схему `public`.

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
```

Затем отдельно выдайте права роли приложения.

```sql
GRANT USAGE, CREATE ON SCHEMA public TO fitness_exercise_catalog_app;
```

### 4. Настроить сервис

В папке `services/exercise-catalog-service` скопируйте `.env.example` и назовите копию
`.env`. Исходный `.env.example` оставьте на месте.

После добавления DataSource и Liquibase откройте `.env` и добавьте настройки базы. Сохраните остальные настройки.
В `DATABASE_PASSWORD` впишите пароль роли из шага 1.

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_exercise_catalog
DATABASE_USERNAME=fitness_exercise_catalog_app
DATABASE_PASSWORD=postgres
SERVER_PORT=8086
```

### 5. Проверить базу и роль в DBeaver

В DBeaver оставайтесь в базе `fitness_exercise_catalog`. Выполните каждый блок отдельно.

Владелец базы должен быть `fitness_exercise_catalog_app`.

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_exercise_catalog';
```

У роли `rolcanlogin` должен быть `true`, остальные три флага — `false`.

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_exercise_catalog_app';
```

Права `can_use` и `can_create` должны быть `true`.

```sql
SELECT has_schema_privilege(
         'fitness_exercise_catalog_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_exercise_catalog_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

Запустите `exercise-catalog-service` с профилем `local`. Пока DataSource и Liquibase не добавлены, статус `UP` подтверждает только запуск сервиса.

В PowerShell проверьте статус `UP`.

```powershell
Invoke-RestMethod http://localhost:8086/actuator/health
```

После добавления и применения миграций проверьте таблицы в DBeaver, в базе `fitness_exercise_catalog`.

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

Отдельно проверьте историю миграций Liquibase.

```sql
SELECT id, author, filename, dateexecuted
FROM public.databasechangelog
ORDER BY dateexecuted;
```

## Правило для следующих сервисов

Каждый сервис получает собственные базу и роль. Схема каждой базы создаётся
Liquibase-миграциями сервиса-владельца; другой сервис не читает и не изменяет
эти таблицы напрямую.

Резервное копирование и восстановление PostgreSQL описаны в
[плане резервного копирования](../system-design/architecture/recovery/56-postgresql-backup-plan.md).
