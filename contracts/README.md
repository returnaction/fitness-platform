# Контракты между сервисами

Здесь находятся машинно-читаемые и версионируемые контракты системы:

- `openapi/` — внешний REST API, которым пользуются Flutter и другие клиенты;
- `proto/` — внутренние gRPC API между сервисами;
- `grpc-contracts/` — Maven-модуль, генерирующий из `proto/` Java message и
  gRPC service stubs;
- в будущем здесь появятся схемы событий Kafka.

Контрактом владеет сервис, который принимает запрос или публикует событие.
Изменение контракта требует обновить связанную спецификацию, тесты и правила
совместимости. Контракт не содержит секреты, настоящие адреса окружений или
данные пользователей.

Текущие контракты:

- [Identity REST API v1](openapi/identity-auth-v1.yaml) — регистрация, вход и обновление сессии;
- [Accounts Creation gRPC v1](proto/accounts-profile-creation-v1.proto);
- [Coins Creation gRPC v1](proto/coins-wallet-creation-v1.proto).

Для `CreateProfileIfAbsent` и `CreateWalletIfAbsent` поля запроса одинаковые. Пример значений
полей protobuf (JSON показан только для наглядности; вызов выполняется по gRPC):

```json
{
  "request_id": "da6add51-47f8-4603-a9ce-17e440a8da64",
  "registration_operation_id": "674970d1-c87d-49c7-938b-cc0d3b044afb",
  "user_id": "4652f81b-2b27-4e99-8c21-4336c716fc09"
}
```

Успешный ответ содержит тот же `user_id` и статус
`CREATION_STATUS_READY`. Повтор использует те же `user_id` и
`registration_operation_id` и не изменяет существующий профиль или кошелёк.
Доступ разрешён Identity через mTLS. Правила повторов и классификация сбоев
зафиксированы в [плане регистрации](../specs/001-user-registration/plan.md).

`grpc-contracts` не запускает сетевой сервер и не содержит бизнес-логики. Это
общий JAR с единственными сгенерированными Java-классами контракта.
Identity, Accounts и Coins подключают его как Maven dependency; вручную
копировать generated-код или `.proto` в сервисы нельзя.

# Контракты между сервисами (gRPC)

## ГЛАВНОЕ ПРАВИЛО (Кто что делает)

1. **Разработчик (Ты)** -> пишет файлы `.proto` в папке `proto/` (это обычный текст, контракт).
2. **Модуль `grpc-contracts`** -> НЕ пишет контракты. Он ЧИТАЕТ `.proto` файлы и ГЕНЕРИРУЕТ из них Java-классы (DTO и Stubs).
3. **Микросервисы (`services/`)** -> НЕ пишут контракты и НЕ генерируют код. Они просто подключают готовый `.jar` от `grpc-contracts` и используют готовые Java-методы.

---

## Как это работает по шагам

### Шаг 1: Пишем контракт
Мы создаем файл в `contracts/proto/accounts-profile-creation-v1.proto`.
Внутри пишем:
- Какие есть методы (например, `CreateProfileIfAbsent`).
- Какие поля в запросе и ответе.

### Шаг 2: Запускаем генерацию
Когда запускается сборка (`mvnw package`), Maven заходит в модуль `grpc-contracts`.
Там в `pom.xml` настроен плагин `protobuf-maven-plugin`.
Этот плагин:
- Берет файл `.proto`.
- Генерирует из него Java-файлы (например, `CreateProfileIfAbsentRequest.java`).
- Упаковывает их в `.jar` файл.

### Шаг 3: Используем в сервисах
Сервис `identity-service` в своем `pom.xml` пишет:
```xml
<dependency>
    <groupId>io.github.returnaction.fitness</groupId>
    <artifactId>fitness-grpc-contracts</artifactId>
    <version>${project.version}</version>
</dependency>
