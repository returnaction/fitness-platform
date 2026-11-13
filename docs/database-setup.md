# Подготовка PostgreSQL для сервисов

Этот документ описывает действия, которые выполняются один раз до первого
запуска сервиса в локальной среде или на сервере. Работа выполняется в
**DBeaver** через подключение PostgreSQL.

Для каждого сервиса создаются:

- отдельная **роль приложения** — технический пользователь PostgreSQL, под
  которым запускается только этот сервис;
- отдельная **база данных** — только этого сервиса;
- доступ роли только к её собственной базе и схеме `public`.


## Identity Service

**База:** `fitness_identity`
**Роль приложения:** `fitness_identity_app`
**Локальный порт:** `8080`

### 1. Создать роль приложения

Здесь создаётся отдельный технический пользователь PostgreSQL
`fitness_identity_app`. Под ним будет подключаться `identity-service`;.

В DBeaver подключиться к PostgreSQL как администратор, например `postgres`, и
выполнить:

```sql
CREATE ROLE fitness_identity_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';
```

### 2. Создать базу данных сервиса

Здесь создаётся отдельная база `fitness_identity`, в которой будут находиться
только таблицы Identity Service. Владельцем базы назначается созданная роль
`fitness_identity_app`.

В DBeaver выполнить этот запрос отдельно при включённом auto-commit:

```sql
CREATE DATABASE fitness_identity
  OWNER fitness_identity_app
  ENCODING 'UTF8'
  TEMPLATE template0;

REVOKE ALL ON DATABASE fitness_identity FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver переключить подключение на базу `fitness_identity`, остаться под
PostgreSQL-администратором и выполнить:

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_identity_app;
```

Это запрещает всем остальным ролям доступ к стандартной схеме `public` и даёт
прикладной роли права, необходимые Liquibase для создания таблиц.

### 4. Настроить сервис

Создать игнорируемый Git файл `services/identity-service/.env` и указать:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_identity
DATABASE_USERNAME=fitness_identity_app
DATABASE_PASSWORD=<пароль-роли>
SERVER_PORT=8080
```

### 5. Проверить базу и роль в DBeaver

Проверить базу и её владельца:

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_identity';
```

Проверить, что прикладная роль не имеет административных прав:

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_identity_app';
```

При подключении к `fitness_identity` проверить права роли на схему:

```sql
SELECT has_schema_privilege(
         'fitness_identity_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_identity_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

После настройки `.env` запустить `identity-service`. При первом запуске
Liquibase подключится к `fitness_identity` и создаст таблицы приложения.

После запуска в PowerShell выполнить health-check:

```powershell
Invoke-WebRequest http://localhost:8080/actuator/health
```

Ожидается HTTP `200` и статус `UP`. Затем в DBeaver, подключённом к
`fitness_identity`, проверить созданные таблицы и историю Liquibase:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;

SELECT id, author, filename, dateexecuted
FROM databasechangelog
ORDER BY dateexecuted;
```

## Accounts Service

**База:** `fitness_accounts`
**Роль приложения:** `fitness_accounts_app`
**Локальный порт:** `8081`

### 1. Создать роль приложения

Здесь создаётся отдельный технический пользователь PostgreSQL
`fitness_accounts_app`. Под ним будет подключаться `accounts-service`; это не
пользователь мобильного приложения.

В DBeaver подключиться к PostgreSQL как администратор, например `postgres`, и
выполнить:

```sql
CREATE ROLE fitness_accounts_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';
```

### 2. Создать базу данных сервиса

Здесь создаётся отдельная база `fitness_accounts`, в которой будут находиться
только таблицы Accounts Service. Владельцем базы назначается созданная роль
`fitness_accounts_app`.

В DBeaver выполнить этот запрос отдельно при включённом auto-commit:

```sql
CREATE DATABASE fitness_accounts
  OWNER fitness_accounts_app
  ENCODING 'UTF8'
  TEMPLATE template0;

REVOKE ALL ON DATABASE fitness_accounts FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver переключить подключение на базу `fitness_accounts`, остаться под
PostgreSQL-администратором и выполнить:

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_accounts_app;
```

Это запрещает всем остальным ролям доступ к стандартной схеме `public` и даёт
прикладной роли права, необходимые Liquibase для создания таблиц.

### 4. Настроить сервис

Создать игнорируемый Git файл `services/accounts-service/.env` и указать:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_accounts
DATABASE_USERNAME=fitness_accounts_app
DATABASE_PASSWORD=<пароль-роли>
SERVER_PORT=8081
```

### 5. Проверить базу и роль в DBeaver

Проверить базу и её владельца:

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_accounts';
```

Проверить, что прикладная роль не имеет административных прав:

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_accounts_app';
```

При подключении к `fitness_accounts` проверить права роли на схему:

```sql
SELECT has_schema_privilege(
         'fitness_accounts_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_accounts_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

После настройки `.env` запустить `accounts-service`. При первом запуске
Liquibase подключится к `fitness_accounts` и создаст таблицы приложения.

После запуска в PowerShell выполнить health-check:

```powershell
Invoke-WebRequest http://localhost:8081/actuator/health
```

Ожидается HTTP `200` и статус `UP`. Затем в DBeaver, подключённом к
`fitness_accounts`, проверить созданные таблицы и историю Liquibase:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;

SELECT id, author, filename, dateexecuted
FROM databasechangelog
ORDER BY dateexecuted;
```

## Coins Service

**База:** `fitness_coins`
**Роль приложения:** `fitness_coins_app`
**Локальный порт:** `8082`

### 1. Создать роль приложения

Здесь создаётся отдельный технический пользователь PostgreSQL
`fitness_coins_app`. Под ним будет подключаться `coins-service`; это не
пользователь мобильного приложения.

В DBeaver подключиться к PostgreSQL как администратор, например `postgres`, и
выполнить:

```sql
CREATE ROLE fitness_coins_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';
```

### 2. Создать базу данных сервиса

Здесь создаётся отдельная база `fitness_coins`, в которой будут находиться
только таблицы Coins Service. Владельцем базы назначается созданная роль
`fitness_coins_app`.

В DBeaver выполнить этот запрос отдельно при включённом auto-commit:

```sql
CREATE DATABASE fitness_coins
  OWNER fitness_coins_app
  ENCODING 'UTF8'
  TEMPLATE template0;

REVOKE ALL ON DATABASE fitness_coins FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver переключить подключение на базу `fitness_coins`, остаться под
PostgreSQL-администратором и выполнить:

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_coins_app;
```

Это запрещает всем остальным ролям доступ к стандартной схеме `public` и даёт
прикладной роли права, необходимые Liquibase для создания таблиц.

### 4. Настроить сервис

Создать игнорируемый Git файл `services/coins-service/.env` и указать:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_coins
DATABASE_USERNAME=fitness_coins_app
DATABASE_PASSWORD=<пароль-роли>
SERVER_PORT=8082
```

### 5. Проверить базу и роль в DBeaver

Проверить базу и её владельца:

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_coins';
```

Проверить, что прикладная роль не имеет административных прав:

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_coins_app';
```

При подключении к `fitness_coins` проверить права роли на схему:

```sql
SELECT has_schema_privilege(
         'fitness_coins_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_coins_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

После настройки `.env` запустить `coins-service`. При первом запуске Liquibase
подключится к `fitness_coins` и создаст таблицы приложения.

После запуска в PowerShell выполнить health-check:

```powershell
Invoke-WebRequest http://localhost:8082/actuator/health
```

Ожидается HTTP `200` и статус `UP`. Затем в DBeaver, подключённом к
`fitness_coins`, проверить созданные таблицы и историю Liquibase:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;

SELECT id, author, filename, dateexecuted
FROM databasechangelog
ORDER BY dateexecuted;
```

## Exercise Catalog Service

**База:** `fitness_exercise_catalog`
**Роль приложения:** `fitness_exercise_catalog_app`
**Локальный порт:** `8086`

### 1. Создать роль приложения

Здесь создаётся отдельный технический пользователь PostgreSQL
`fitness_exercise_catalog_app`. Под ним будет подключаться
`exercise-catalog-service`; это не пользователь мобильного приложения.

В DBeaver подключиться к PostgreSQL как администратор, например `postgres`, и
выполнить:

```sql
CREATE ROLE fitness_exercise_catalog_app
  LOGIN
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE
  NOINHERIT
  PASSWORD '<длинный-уникальный-пароль>';
```

### 2. Создать базу данных сервиса

Здесь создаётся отдельная база `fitness_exercise_catalog`, в которой будут
находиться только таблицы Exercise Catalog Service. Владельцем базы назначается
созданная роль `fitness_exercise_catalog_app`.

В DBeaver выполнить этот запрос отдельно при включённом auto-commit:

```sql
CREATE DATABASE fitness_exercise_catalog
  OWNER fitness_exercise_catalog_app
  ENCODING 'UTF8'
  TEMPLATE template0;

REVOKE ALL ON DATABASE fitness_exercise_catalog FROM PUBLIC;
```

### 3. Настроить права схемы

В DBeaver переключить подключение на базу `fitness_exercise_catalog`, остаться
под PostgreSQL-администратором и выполнить:

```sql
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO fitness_exercise_catalog_app;
```

Это запрещает всем остальным ролям доступ к стандартной схеме `public` и даёт
прикладной роли права, необходимые Liquibase для создания таблиц.

### 4. Настроить сервис

После добавления в сервис DataSource и Liquibase создать игнорируемый Git файл
`services/exercise-catalog-service/.env` и указать:

```properties
DATABASE_URL=jdbc:postgresql://localhost:5432/fitness_exercise_catalog
DATABASE_USERNAME=fitness_exercise_catalog_app
DATABASE_PASSWORD=<пароль-роли>
SERVER_PORT=8086
```

### 5. Проверить базу и роль в DBeaver

Проверить базу и её владельца:

```sql
SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname = 'fitness_exercise_catalog';
```

Проверить, что прикладная роль не имеет административных прав:

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles
WHERE rolname = 'fitness_exercise_catalog_app';
```

При подключении к `fitness_exercise_catalog` проверить права роли на схему:

```sql
SELECT has_schema_privilege(
         'fitness_exercise_catalog_app', 'public', 'USAGE'
       ) AS can_use,
       has_schema_privilege(
         'fitness_exercise_catalog_app', 'public', 'CREATE'
       ) AS can_create;
```

### 6. Запустить и проверить микросервис

После реализации DataSource и Liquibase запустить `exercise-catalog-service`.
При первом запуске Liquibase подключится к `fitness_exercise_catalog` и создаст
таблицы приложения. Пока эти части не реализованы, health-check подтверждает
только запуск шаблона, но не подключение к базе.

После запуска в PowerShell выполнить health-check:

```powershell
Invoke-WebRequest http://localhost:8086/actuator/health
```

Ожидается HTTP `200` и статус `UP`. После появления и применения миграций в
DBeaver, подключённом к `fitness_exercise_catalog`, проверить таблицы и
историю Liquibase:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;

SELECT id, author, filename, dateexecuted
FROM databasechangelog
ORDER BY dateexecuted;
```

## Правило для следующих сервисов

Каждый сервис получает собственные базу и роль. Схема каждой базы создаётся
Liquibase-миграциями сервиса-владельца; другой сервис не читает и не изменяет
эти таблицы напрямую.

Резервное копирование и восстановление PostgreSQL описаны в
[плане резервного копирования](../system-design/architecture/recovery/56-postgresql-backup-plan.md).
