# Фактическая схема: сверка 2026-11-12

Это описание исходного кода до реализации Spec 013. Целевое поведение logout-all
изменено по итогам grilling в [spec.md](spec.md): прежние отказы после допуска
Gateway по exp, исходной сессии и статусу ACTIVE не сохраняются.

Ветка 39-gateway-refactor. До исследования изменены только AGENTS.md и заготовка
013-pending, созданные в этом чате. Код сервисов не менялся в рамках этой работы.
Пользователь подтвердил: локально Java-процессы без контейнеров; серверный запуск
позже в контейнерах. Количество серверов и система запуска контейнеров пока неизвестны.

## Текущий путь

Клиент → Gateway HTTP → Identity HTTP. Пять POST-операций каталога: register,
login, refresh, logout, logout-all. Первые четыре без JWT/CheckSession в Gateway;
Authorization удаляется, тело сохраняется. Logout-all: JWT + gRPC CheckSession
с mTLS, затем исходный Bearer Identity и повторная проверка JWT там.
Identity вызывает Accounts/Coins по mTLS gRPC для создания профиля/кошелька.
Пользовательских маршрутов Gateway → Accounts/Coins нет.

Шаблоны окружения: Gateway 127.0.0.1:8088, management 127.0.0.1:9088;
Identity HTTP 8080, Accounts HTTP 8081, Coins HTTP 8082. Их HTTP bind необходимо
явно ограничить: наличие localhost в URL клиента не ограничивает сервер.
gRPC в шаблонах привязан к 127.0.0.1: Identity 9093, Accounts 9091, Coins 9092.
Это значения шаблонов, не доказательство активных listeners или firewall.
PostgreSQL локально вне Docker, отдельные БД сервисов. E2E-скрипт имеет отдельный
одноразовый тестовый PostgreSQL; он не определяет схему обычного запуска.

## Подтверждённые точки изменения

Пути ниже относительно корня репозитория; имена Java-классов однозначны в модулях.

| Модуль / место | Что требуется |
| --- | --- |
| gateway-service: forwarding/HttpTransportConfiguration.java | Настроить mTLS Apache HTTP client, сохранить deadline и отсутствие повторов |
| gateway-service: security/jwks/JwksInitializer.java | Отдельный java.net.http.HttpClient: mTLS, проверка имени; сохранить retry/readiness |
| gateway-service: configuration/properties/UpstreamProperties.java, AccessTokenProperties.java | Требовать защищённые адреса в рабочей конфигурации, валидировать TLS material |
| identity-service: security/config/SecurityConfiguration.java | Проверка TLS caller до пользовательского контекста, явная матрица методов/путей; отделить management/recovery |
| identity-service: security/jwt/LogoutAllAuthenticationFilter.java | После security gate разбор доверенного контекста, Authentication с ролями |
| identity-service: session/controller/LogoutController.java, session/service/LogoutService.java | Убрать зависимость principal от AccessTokenVerifier.Identity; изменить повторные отказы по spec.md, сохранить транзакцию и блокировки |
| identity-service: security/jwt/AccessTokenVerifier.java, registration/security/RecoveryAccessVerifier.java | Сохранить полную JWT-проверку recovery и проверки SUPER_ADMIN/сессии |
| identity-service: session/repository/RefreshTokenRepository.java, SessionVerificationRepository.java | Проверить гонки, сохранить необходимые блокировки; повторные отказы logout-all изменить по spec.md |
| application*.yaml и .env.example Gateway/Identity | TLS, bind, отдельный management, явные имена и сертификаты |
| application*.yaml и .env.example Accounts/Coins | Только необходимое ограничение HTTP/management bind; не переводить пользовательскую security |
| gateway-service/src/test/python/verify_gateway_e2e.py | Источник существующих сценариев; при необходимости переиспользовать для проверки локального приложения, без обязательного отдельного стенда |
| существующие HTTP/security/gRPC тесты Gateway и Identity | TLS transport, роли, отрицательные права, SecurityContext и гонки |

Существующие SessionCallerAuthorization и IdentityAuthorizationInterceptor
проверяют URI SAN. Нужно сохранить ограничения зарегистрированных gRPC методов;
не переносить доверие к caller на любые будущие RPC автоматически.

Исправления Spec 012 подтверждены: RouteValidationFilter создаёт matcher'ы
в конструкторе; GatewayRouteValidator вызывается при создании маршрутов;
RouteAccessPolicy не зависит от UpstreamProperties; wildcard imports в
configuration/properties не найдены. Повторять эти изменения не требуется.

## Источники и ограничения

- [Spec 012](../012-gateway-refactor/spec.md), [план](../012-gateway-refactor/plan.md), [задачи](../012-gateway-refactor/tasks.md).
- [Gateway README](../../services/gateway-service/README.md).
- [Контракт Gateway](../../contracts/gateway-v1.md).
- [Сессии Identity](../../system-design/services/identity/31-identity-login-sessions.md).
- [Внутренние API](../../system-design/interactions/59-identity-accounts-internal-api.md).
- [Доступ](../../system-design/architecture/access/15-auth-gateway-draft.md).
- [Развёртывание](../../system-design/architecture/deployment/README.md).

Документ доступа местами ещё ссылается на Spec 009 и описывает service JWT как
кандидата для будущих вызовов. Для текущих регистрационных gRPC уже выбран mTLS;
для HTTP Gateway → Identity Spec 013 фиксирует отдельный mTLS-путь без service JWT.
Обновление нормативных контрактов выполнить после согласования спецификации.
На этом этапе тесты не запускались, серверная среда не обследована, секреты
.env не выводились. Исторические результаты Spec 012 не выданы за новые проверки.
