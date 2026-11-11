# Задачи: переписывание Accounts Service по инженерному стандарту

> **Спецификация:** [spec.md](spec.md)  
> **План:** [plan.md](plan.md)  
> **Статус:** complete

Задачи остаются открытыми, пока не выполнена указанная проверка.

## Подготовка

- [x] T000: Зафиксировать текущие результаты `EnsureProfile`, транзакционные границы, ограничения таблицы и gRPC-ошибки; составить список подтверждённых несоответствий Accounts инженерному стандарту.
- [x] T001 (после T000): Спроектировать JPA entity/repository и обработку конфликтов уникальности, сохраняя существующие правила гонок и повторов.

## Реализация

- [x] T002 (после T001): Заменить `JdbcClient` в `ProfileProvisioningService` на Spring Data JPA/Hibernate; не менять protobuf v1, schema и mTLS-границу.
- [x] T003 (после T002): Переписать тесты Accounts, использующие `JdbcClient`, на поддерживаемый JPA-подход без снижения покрытия PostgreSQL-сценариев.
- [x] T004 (после T002): Устранить подтверждённые сопутствующие нарушения стандарта, не расширяя scope новыми профайл-API или контрактами. Решение 11 ноября 2026: gRPC `EnsureProfile` обязателен при каждом запуске; `accounts.grpc.enabled` и условная регистрация сервера удалены, поэтому отсутствие либо некорректность mTLS-материалов останавливает запуск. Исчерпание попыток уникальной генерации выделено в `ProfileCreationExhaustedException` и возвращает retryable gRPC `ABORTED`; прочие runtime-сбои остаются `INTERNAL` без внутренних деталей. Автоматический сквозной тест использует PostgreSQL, фактический `SecureGrpcServer`, mTLS-сертификат Identity и проверяет запись профиля.

## Проверка и выпуск

- [x] T005 (после T003, T004): Выполнить unit, gRPC и PostgreSQL-проверки миграции, повторов, коллизий и параллельных вызовов. Подтверждено 11 ноября 2026: `mvn -pl services/accounts-service -am test`; отдельно выполнены `AccountsProvisioningGrpcServiceTest` и `ProfileProvisioningIntegrationTest`, включая PostgreSQL + реальный gRPC + mTLS.
- [x] T006 (после T005): Запустить Maven Wrapper `verify`, Spotless и SpotBugs; записать фактические результаты и ограничения. 11 ноября 2026 `./mvnw verify` прошёл с `JAVA_TOOL_OPTIONS=-Duser.home=C:\Users\Oberg`; `spotless:check` прошёл после обновления общего Eclipse Formatter profile, `spotbugs:check` завершился без предупреждений.
- [x] T007 (после T006): Сверить критерии `spec.md`, обновить связанные документы только при изменении контракта и зафиксировать статус спецификации. Изменение retryable gRPC `ABORTED` описано в контракте регистрации Identity; спецификация 010 завершена.

## Связанные Pull Request

| Задача | Ветка | Pull Request | Статус |
| --- | --- | --- | --- |
| Переписывание Accounts Service | `36-accounts-service-rewrite` | — | draft |
