# Контракты между сервисами

Здесь находятся машинно-читаемые и версионируемые контракты будущей системы:

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

- [Identity REST API v1](openapi/identity-auth-v1.yaml) — регистрация;
- [Accounts Provisioning gRPC v1](proto/accounts-provisioning-v1.proto);
- [Coins Provisioning gRPC v1](proto/coins-provisioning-v1.proto).

`grpc-contracts` не запускает сетевой сервер и не содержит бизнес-логики. Это
общий JAR с единственными сгенерированными Java-классами контракта. Будущие
Identity, Accounts и Coins подключают его как Maven dependency; вручную
копировать generated-код или `.proto` в сервисы нельзя.
