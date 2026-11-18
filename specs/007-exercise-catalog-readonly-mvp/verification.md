# Проверки спецификации 007

## 2026-11-18 — локальная HTTP/Security-цепочка Catalog (T013-A)

Реализованы пакеты shared/security и shared/web по структуре Coins:
RequestIdFilter с отдельной регистрацией до Security, фильтр извлечения sub
и ролей из Bearer, stateless-правила доступа, SecurityExceptionHandler,
общий JSON ошибок и interceptor запрета query-параметров.
Контекст очищается в finally. Подпись, срок, issuer и audience JWT проверяет
Gateway; Catalog их повторно не проверяет. GET полного каталога требует
контекста, остальные API запрещены. Служебные пути разрешены отдельно.

Формат ошибок: error с code, message, details и requestId. Добавлены ответы
400/401/403, обработчики MVC 405/406/415, 503 при невозможности открыть
транзакцию и общий 500. Для 406 тело отсутствует, как в Coins. Специфичные
ошибки кошелька не переносились. NoStoreResponseInterceptor не добавлялся;
это не означает, что HTTP-кеширование разрешено — настройки Security сохранены.

Выполнена чистая компиляция:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=C:\Users\Oberg\.m2\repository' -pl services/exercise-catalog-service clean compile -DskipTests
```

Результат: BUILD SUCCESS, скомпилированы 23 Java-файла. После ошибки доступа
к зависимостям в песочнице команда выполнена с разрешённым доступом.
Это проверка рабочей копии на момент выполнения, не гарантия для последующих
изменений. Проверка diff прошла.

По просьбе пользователя тесты не запускались. Сервис и миграции не запускались,
пользовательские БД не изменялись. HTTP-поведение, маршрут Gateway и сетевое
ограничение прямого доступа не проверены. T013-A завершена только в объёме
реализации и компиляции; T013-B, T013-C и общая T013 остаются открытыми.

## 2026-11-18 — Маршрут Gateway (T013-C)

В Gateway зарегистрирован GET `/api/v1/exercise-catalog` с политикой
`AUTHENTICATED` и адресом сервиса из `EXERCISE_CATALOG_HTTP_BASE_URL`.
Maven Wrapper (`-pl services/gateway-service -am test-compile -DskipTests`)
успешно скомпилировал основной и тестовый код. Тесты и сквозной запрос
не запускались. Проверка сетевой изоляции Catalog остаётся открытой.

## 2026-11-18 — Полный GET через Gateway (T010, T013-B/C)

Сборка Maven Wrapper с `package -DskipTests` прошла. Для отдельной HTTP-проверки
запущены Gateway, Identity, Accounts, Coins и Catalog с временным PostgreSQL 17,
миграциями Liquibase, тестовым аккаунтом и временными сертификатами.

- После регистрации и входа GET через Gateway вернул 200, две категории,
  шесть упражнений и двенадцать вариаций. У всех шести упражнений
  `defaultVariationId` совпал с ID вариации STANDARD. Заголовок Cache-Control отсутствовал.
- Без токена тот же GET вернул 401 и `invalid_access_token`. UUID в X-Request-Id
  совпал с error.requestId.

Проверка выполнена на локальном тестовом стенде. Сетевую изоляцию на VPS,
пустой каталог, фильтрацию неактивных записей и число SQL-запросов этот прогон не проверял.

## 2026-11-18 — T010: чтение полного каталога

`./mvnw -pl services/exercise-catalog-service -Dtest=ExerciseCatalogReadIntegrationTest test`
завершилась с BUILD SUCCESS. Все три теста прошли без пропусков. Каждый отправлял
GET в работающий Catalog с временной PostgreSQL 17 и проверял ответ 200.

- Стартовые данные: две категории, шесть упражнений, двенадцать вариаций.
  У каждого упражнения defaultVariationId указывает на его STANDARD.
- Пустая БД: пустой массив categories.
- Частично отключённый каталог: активные пустые категории сохранены, неактивные
  записи и упражнения без активного STANDARD исключены. На всех трёх уровнях
  подтверждён порядок по sort_order, затем по id, включая совпадающий sort_order.

Во всех трёх сценариях Hibernate подготовил ровно три SQL-оператора за запрос,
и JSON не содержал поля unit. Сквозная проверка Gateway записана выше.

## 2026-11-18 — T004–T008: конфигурация, миграции и стартовые данные

Профили local/test и шаблон env присутствуют, локальный .env исключён из Git.
Запуск Spring с DataSource и Liquibase на чистой тестовой БД подтверждён в T010.

`./mvnw -pl services/exercise-catalog-service -Dtest=CatalogSchemaMigrationIntegrationTest,CatalogSeedDataIntegrationTest test`
на временной PostgreSQL 17: 5 тестов прошли, ошибок и пропусков 0.
Первый прогон выявил устаревшее ожидание трёх changeset и конфликты тестовых
ID и кодов со стартовыми данными. Исправлены тестовые записи, количество
changeset и проверки SQLSTATE. Миграции и стартовые данные не менялись.

- Созданы три таблицы с ожидаемыми индексами. Проверены уникальность, внешние
  ключи и запрет удаления родителя, ограничения ID, метрики, порядка, версии,
  целевого диапазона и шага.
- Первый запуск применил четыре changeset. При повторном запуске применено ноль.
  В таблицах остались 2 категории, 6 упражнений и 12 вариаций, у каждого упражнения
  ровно одна активная STANDARD.
- Все 20 строк исходного seed SQL отдельно сверены по полям с таблицами T008,
  включая названия, описания, инструкции, порядок, диапазоны, активность и версию.
  Сверка подтверждает соответствие записанной спецификации.

## 2026-11-18 — T013: локальная проверка Gateway и Catalog

Целевой прогон Maven Wrapper с `-pl services/gateway-service,services/exercise-catalog-service -am`,
`-Dtest=ExerciseCatalogServiceApplicationTest,JwtValidationTest,RouteValidationTest`
и `-Dsurefire.failIfNoSpecifiedTests=false test` завершился успешно.
Прошли 16 тестов JWT Gateway, 5 маршрутизации Gateway и 5 HTTP/Security Catalog, без пропусков.

Gateway вернул 401 для каталога при неверной подписи JWT. Проверка сессии и
вызов upstream Catalog не выполнялись. Тесты маршрутизации проверили запрет query.
Предыдущий сквозной прогон с реальной сессией зафиксирован выше.

В тестах Catalog работали HTTP, MVC и Spring Security, сервис чтения был подменён.
Проверены 401 для отсутствующего и некорректного Bearer, 400 для query, 403 для
аутентифицированного POST, 406 без тела для неподдерживаемого Accept и
503 с Retry-After при имитации ошибки открытия транзакции. Проверены поля JSON
ошибки, соответствие requestId заголовку, публичные health/probes/info/prometheus
и отказ анонимному запросу к actuator/env. Отдельная проверка фильтра подтвердила
очистку SecurityContext после исключения в цепочке. Проверка внешнего доступа
к порту Catalog выполняется на VPS в T022.

## 2026-11-18 — T019: итоговая локальная проверка

Maven Wrapper с JDK 25.0.5 и Docker: `./mvnw --batch-mode --no-transfer-progress verify`.
Первые девять модулей прошли. У Catalog прошли тесты, затем SpotBugs 4.10.5.0
выдал шесть замечаний EI_EXPOSE_REP/EI_EXPOSE_REP2 о списках в DTO.
В конструкторах ExerciseCatalogResponse, Category и Exercise добавлен List.copyOf.
После исправления выполнен `./mvnw --batch-mode --no-transfer-progress verify -rf :exercise-catalog-service`.
Catalog и Notifications прошли, результат BUILD SUCCESS. Таким образом проверены
все 11 модулей реактора. У Catalog 13 тестов без пропусков, у Gateway 49.
Всего по итоговым результатам модулей 542 теста прошли, три пропущены.
ProfileCreationClientLocalTest, WalletCreationClientLocalTest и LocalStepCaSmokeTest
требуют явного включения fitness.localStepCaSmoke. Их локальные сценарии
с сертификатами в этом прогоне не проверялись.

git diff --check прошёл. Spotless отключён согласно инженерному стандарту.
Штатный scripts/verify-markdown-links.ps1 в рабочем каталоге нашёл 12 битых
ссылок только в игнорируемой копии target/AGENTS.local-backup.md. Тот же скрипт
на временном снимке отслеживаемых и новых неигнорируемых файлов успешно проверил
199 Markdown-файлов. Описание роли и базы сверено с docs/database-setup.md.
В миграциях три прикладные таблицы каталога, внешний контракт содержит один GET.
Серверные проверки T020–T022 остаются открытыми.
