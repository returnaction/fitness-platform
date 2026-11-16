# План Spec 017

> **Статус:** completed — реализация и локальная сквозная проверка выполнены.
> **Основание:** [spec.md](spec.md).
> **Стандарты:** [engineering-standards](../../docs/engineering-standards.md).

1. Перед реализацией перечитать документы Coins, код coins-service, каталог и
   фильтры gateway-service. Сверить состояние `spec.md` с файлами; найденные
   посторонние проблемы записать отдельно, не расширяя задачу автоматически.
2. После отдельного разрешения сначала создать owner OpenAPI-контракт Coins,
   затем добавить Gateway `$ref` и проверить контракты. Каждый из этих шагов —
   отдельная подзадача T002.
3. Выполнить Gateway в порядке: ID upstream, конфигурация всех сред, запись
   каталога маршрутов, проверка security forwarding, proxy-тест с mock upstream
   и verify. Gateway не считается готовым к использованию, пока не завершён
   Coins; каждый шаг — отдельная подзадача T003.
4. Выполнить Coins в порядке: DTO, извлечение claims, security chain, repository,
   application service, решение о mapper, controller, общий error response,
   `RestControllerAdvice`, repository-тесты, web/security-тесты и verify.
   Gateway остаётся единственным валидатором JWT и сессии; Coins извлекает claims
   из forwarded Bearer JWT. Каждый шаг — отдельная подзадача T004.
5. Сквозную проверку разделить на подготовку данных, успешный запрос, security,
   ошибки Coins через Gateway и запись доказательств в verification.md.
   После успешной сквозной проверки обновить `postman/fitness-local.postman_collection.json`:
   добавить авторизованный GET баланса через Gateway и проверки успешного ответа.
   Не создавать Docker или серверное развёртывание; не запускать миграции
   пользовательских БД без отдельного разрешения.
6. Записать команды, результаты и ограничения в [verification.md](verification.md).
   Выполнить Maven verify затронутых модулей, Spotless/SpotBugs,
   `scripts/verify-markdown-links.ps1` и `git diff --check`.

T001 выполнена; результаты сверки и проверки ссылок записаны в verification.md.
Commit, push, merge, deploy и
миграции пользовательских БД не выполнялись.
