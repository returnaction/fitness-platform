# Exercise Catalog Service

Сервис отдаёт активные категории, упражнения и вариации одним запросом
`GET /api/v1/exercise-catalog`. Клиент обращается к Gateway с Bearer-токеном.
Сервис хранит каталог в собственной PostgreSQL и применяет Liquibase-миграции
схемы и стартовых данных при запуске. [Контракт OpenAPI](../../contracts/openapi/exercise-catalog-v1.yaml)

## Локальный запуск

1. Подготовьте базу и роль по [инструкции PostgreSQL](../../docs/database-setup.md#exercise-catalog-service).
2. Создайте `.env` в каталоге модуля по [.env.example](.env.example) и задайте пароль роли.
3. Откройте корневой `pom.xml` в IntelliJ IDEA и запустите `ExerciseCatalogServiceApplication`.
   Локальный профиль использует порт `8086` из шаблона. При запущенных Identity
   и Gateway запрос доступен через `http://localhost:8088/api/v1/exercise-catalog`.
   [Коллекция Postman](../../postman/fitness-local.postman_collection.json)

## Выкладка

Compose запускает сервис во внутренней сети с профилем `dev`.
Gateway обращается к нему по адресу `http://exercise-catalog-service:8086`.
Порядок выпуска, настройки VPS и проверки после запуска описаны в
[инструкции по выкладке](../../deployment/README.md).
