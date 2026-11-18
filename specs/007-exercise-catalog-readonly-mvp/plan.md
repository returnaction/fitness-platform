# План реализации: read-only MVP каталога упражнений

> **Спецификация:** [spec.md](spec.md)  
> **Статус:** implemented — реализация принята 2026-11-18. Первый выпуск на VPS ожидает push и merge.
> **Связанные документы system-design:** [модель Catalog](../../system-design/services/exercise-catalog/57-exercise-catalog-service.md), [инженерные стандарты](../../docs/engineering-standards.md)

## Краткое решение

`exercise-catalog-service` получает собственную PostgreSQL-базу и Liquibase.
Клиент получает активные категории, упражнения и вариации одним GET через Gateway.

Порядок работ: конфигурация БД → миграции и seed → чтение и сборка DTO →
маршрут Gateway → проверки → раскатка Catalog по T020–T022.

## Затрагиваемые границы

| Область | Изменение | Владелец |
| --- | --- | --- |
| Exercise Catalog | Хранит справочник, отдаёт полный каталог по REST. | Catalog |
| PostgreSQL | `fitness_exercise_catalog` и `fitness_exercise_catalog_app`; схему создаёт Liquibase. | Catalog |
| API Gateway | Единственная внешняя точка REST. Валидирует пользовательский JWT и направляет разрешённый запрос в Catalog. | Gateway |
| Клиент | Передаёт access token только API Gateway; к Catalog напрямую не подключается. | Клиент |

## Данные и миграции

| Таблица | Назначение |
| --- | --- |
| `catalog_categories` | `SMALLINT` identity PK, уникальный неизменяемый code, name, is_active, sort_order, version, timestamps. |
| `catalog_exercises` | `SMALLINT` identity PK, локальный `SMALLINT` FK на категорию, code, name, description, primary_metric, is_active, sort_order, version, timestamps. |
| `catalog_variations` | `SMALLINT` identity PK, локальный `SMALLINT` FK на упражнение, code, name, description, instructions, min/max/step, is_active, sort_order, version, timestamps. |

Ограничения следуют контракту Catalog: уникальные коды, две метрики, положительная
цель и шаг, `max_target >= min_target`. В MVP не создаются
`catalog_admin_requests`, `catalog_audit`, `catalog_outbox`.

Seed-данные — отдельный Liquibase changeset. До него владелец продукта
утверждает полный набор текстов, кодов и правил: junior не сочиняет критерии
техники. Миграция проверяется на пустом PostgreSQL.

### Карта файлов Liquibase для junior

```text
services/exercise-catalog-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changes/
    ├── 001-create-catalog-categories.yaml
    ├── 001-create-catalog-categories.sql
    ├── 002-create-catalog-exercises.yaml
    ├── 002-create-catalog-exercises.sql
    ├── 003-create-catalog-variations.yaml
    ├── 003-create-catalog-variations.sql
    ├── 004-seed-initial-exercise-catalog.yaml
    └── 004-seed-initial-exercise-catalog.sql
```

Каждый YAML-файл содержит один Liquibase changeset и подключает одноимённый
SQL-файл, как в существующих Identity, Accounts и Coins. `master` подключает
changeset-ы строго в числовом порядке. Изменять уже применённый changeset
нельзя: следующая правка всегда создаёт новый номер.

## Структура сервиса

Следуем инженерным стандартам: группировка по функции и обычные Spring-слои,
без лишней второй архитектуры.

```text
io.github.returnaction.fitness.exercisecatalog/
  catalog/
    controller/     # REST
    dto/            # ExerciseCatalogResponse с вложенными records
    service/        # чтение каталога
    repository/     # Spring Data JPA
    entity/         # category, exercise, variation, metric
    mapper/         # entity → DTO
    exception/
  shared/
    security/       # SecurityConfiguration
    web/
      requestid/    # RequestIdFilter и RequestIdResolver
  configuration/
```

Текущие общие компоненты:
[SecurityConfiguration](../../services/exercise-catalog-service/src/main/java/io/github/returnaction/fitness/exercisecatalog/shared/security/SecurityConfiguration.java),
[RequestIdFilter](../../services/exercise-catalog-service/src/main/java/io/github/returnaction/fitness/exercisecatalog/shared/web/requestid/RequestIdFilter.java)
и [RequestIdResolver](../../services/exercise-catalog-service/src/main/java/io/github/returnaction/fitness/exercisecatalog/shared/web/requestid/RequestIdResolver.java).
RequestIdFilter зарегистрирован через ServletFiltersConfiguration до Security,
без дублирующего Component. Общий ApiExceptionHandler и SecurityExceptionHandler
формируют ошибки в формате Coins. CatalogAuthenticationFilter создаёт контекст
из Bearer, предварительно проверенного Gateway; подпись сам не проверяет.

## Контракты и взаимодействия

Решение от 2026-11-17: `ExerciseCatalogController` вызывает
`ExerciseCatalogService.getExercises()`. Сервис читает активные категории,
упражнения и вариации тремя repository-запросами, каждый с сортировкой
`sortOrder ASC, id ASC`. Чтение и сборка DTO выполняются в
`@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`:
три чтения используют один снимок PostgreSQL. Интеграционный тест T010 подтвердил
ровно три SQL-запроса на каждом из трёх проверенных наборов данных.

Сервис группирует вариации по exercise ID, упражнения по category ID,
исключает упражнения без активного STANDARD. Mapper переносит поля в
вложенные records `Category`, `Exercise`, `Variation` общего
`ExerciseCatalogResponse`. Entity не сериализуются в HTTP напрямую.
Точный состав ответа описан в `spec.md`; REST-поле `unit` удалено,
`defaultVariationId` сохранено.

| Направление | Тип | MVP-контракт | Совместимость |
| --- | --- | --- | --- |
| Клиент → Gateway | REST | `GET /api/v1/exercise-catalog` с Bearer access token | Полный вложенный каталог без пагинации; Gateway валидирует JWT и отвечает `401`. |
| Gateway → Catalog | Внутренний HTTP | Тот же read-only REST после успешной проверки Gateway | Catalog не валидирует пользовательский JWT; доверяет только внутреннему Gateway. |

## Надёжность и безопасность

- API Gateway валидирует пользовательский JWT для всех внешних read-only
  запросов Catalog. Catalog не содержит повторной проверки подписи JWT,
  но извлекает sub и роли в SecurityContext по модели Coins (решение 2026-11-18).
- REST-порт Catalog не открывается в интернет: к нему допускается только
  Gateway во внутренней сети. Механизм удостоверения Gateway фиксируется в
  его контракте и не заменяется доверительным пользовательским заголовком.
- В логах нет пароля БД, токенов и service credentials.

## Проверка

- Проверить сборку вложенного DTO и соответствие defaultVariationId активной вариации STANDARD.
- PostgreSQL/Testcontainers: Liquibase, FK/unique/check constraints, сортировка,
  фильтрация активности, стабильная сортировка, согласованный снимок и отсутствие N+1.
- HTTP Catalog: полный и пустой ответы, DTO без выключенных записей и упражнений
  без активного STANDARD, отсутствие поля unit. На уровне Gateway:
  действующий JWT проходит, отсутствующий или неверный JWT получает `401`. При раскатке на VPS
  проверить отсутствие прямого внешнего доступа к Catalog (T022).

## Риски и решения

| Риск | Решение / владелец |
| --- | --- |
| Неполные инструкции | Владелец продукта утверждает seed-данные до миграции. |
| Рост полного каталога | Контролировать размер ответа; пагинация и ETag не входят в текущую реализацию. |
| Прямой доступ к Catalog | Подтвердить, что извне доступен только Gateway. |
| В старом документе есть другие имена таблиц | Использовать `SMALLINT` ID и `catalog_*` из `57-exercise-catalog-service.md` — единственного источника таблиц. |
| Catalog недоступен | Проверить HTTP-ошибку при отказе чтения каталога. |
