# Фактическая схема после T002b — 2026-11-12

Ветка 39-gateway-refactor. Локально отдельные Java-процессы. Этот документ
описывает состояние после возврата HTTP, а не первоначальный проект HTTP mTLS.
Серверный запуск в Docker сейчас не подготавливается.

## Текущий путь

Клиент → Gateway HTTP → Identity HTTP. Каталог содержит пять POST: register,
login, refresh, logout, logout-all. Для первых четырёх Gateway удаляет Authorization,
не проверяет JWT/сессию и сохраняет тело. Для logout-all проверяет JWT и вызывает
CheckSession по существующему gRPC mTLS, затем передаёт исходный Bearer в Identity.
Identity повторно проверяет JWT; LogoutService сохраняет проверки срока токена,
пользователя и сессии в транзакции. Новые правила grilling ещё не реализованы.

Identity вызывает Accounts/Coins по существующему mTLS gRPC для создания
профиля/кошелька. Пользовательских маршрутов Gateway → Accounts/Coins нет.
HTTP caller Identity не аутентифицируется как сервис: другой локальный процесс
может вызвать разрешённый HTTP API. Allowlist операций не является проверкой caller.
Rate limiter Identity удалён; recovery закрыт, его бизнес-код сохранён.

Шаблоны локального окружения: Gateway 127.0.0.1:8088, management 9088;
Identity HTTP 8080 и отдельный management 9080; Accounts HTTP 8081, Coins HTTP 8082.
Внутренний HTTP в local привязан к loopback. gRPC в шаблонах: Identity 9093,
Accounts 9091, Coins 9092, на 127.0.0.1. Значения конфигурации не доказывают
активные listeners или firewall сервера. PostgreSQL локально вне Docker.

## Точки кода

Пути и классы ниже указаны относительно соответствующего модуля services.
Это описание состояния, не список новых поручений.

| Модуль / место | Текущее назначение |
| --- | --- |
| identity-service: security/HttpOperationFilter.java | Allowlist методов/путей, разделение API/management, закрытый recovery; без HTTP-сертификатов |
| identity-service: security/config/SecurityConfiguration.java | Подключение security-фильтров |
| identity-service: security/jwt/LogoutAllAuthenticationFilter.java, AccessTokenVerifier.java | Полная JWT-аутентификация logout-all сохраняется |
| identity-service: session/service/LogoutService.java | Текущие бизнес-проверки, транзакция и отзыв сессий |
| identity-service: registration/security/RecoveryAccessVerifier.java | Проверки JWT, сессии и SUPER_ADMIN сохранены для закрытого recovery |
| identity-service: application.yaml, application-local.yaml, .env.example | Обычный HTTP, локальные адреса и отдельный management |
| gateway-service: forwarding/HttpTransportConfiguration.java, security/jwks/JwksInitializer.java | HTTP forwarding и загрузка JWKS; отменённый T003 не реализован |
| Accounts/Coins: application-local.yaml | Локальный HTTP bind; пользовательская security массово не менялась |

Существующие SessionCallerAuthorization и IdentityAuthorizationInterceptor
сохраняют проверку caller для gRPC. Разрешение одного метода не означает
автоматического разрешения будущих RPC.

## Свидетельства

Результаты T002a и T002b находятся в [tasks.md](tasks.md). После T002b проверены
шесть адресных тестов, Spotless/SpotBugs и семь HTTP-проверок реальных
Identity/Gateway. Полный набор бизнес-тестов после возврата HTTP не перезапускался.
[Отчёт T002](verification-t002.md) исторический: его HTTP mTLS впоследствии удалён.
Защита развёрнутой среды не проверена. В обновлении документов новые тесты
приложения не запускались.

## Источники и ограничения

- [Spec 012](../012-gateway-refactor/spec.md), [план](../012-gateway-refactor/plan.md), [задачи](../012-gateway-refactor/tasks.md).
- [Gateway README](../../services/gateway-service/README.md).
- [Контракт Gateway](../../contracts/gateway-v1.md).
- [Сессии Identity](../../system-design/services/identity/31-identity-login-sessions.md).
- [Внутренние API](../../system-design/interactions/59-identity-accounts-internal-api.md).
- [Доступ](../../system-design/architecture/access/15-auth-gateway-draft.md).
- [Развёртывание](../../system-design/architecture/deployment/README.md).
