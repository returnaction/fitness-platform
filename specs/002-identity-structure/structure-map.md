# Карта классов identity-service

> Снимок исходного кода для спецификации 002; внутренние типы классов не перечислены.

## Пакеты и классы

| Пакет относительно `identity` | Классы |
| --- | --- |
| `.` | `IdentityServiceApplication` |
| `registration/controller` | `RegistrationController`, `RegistrationRecoveryController` |
| `registration/dto` | `RegisteredUser`, `RegisterResponse`, `RegisterUserRequest`, `RegistrationRecoveryResponse`, `RegistrationTokenResponse`, `ResumeRegistrationRequest`, `ResumeRegistrationResponse` |
| `registration/entity/operation` | `RegistrationOperationEntity`, `RegistrationOperationStatus` |
| `registration/entity/role` | `RoleCode`, `RoleEntity`, `UserRoleEntity`, `UserRoleId` |
| `registration/entity/user` | `UserCredentialEntity`, `UserEntity`, `UserStatus` |
| `registration/exception` | `InvalidIdempotencyKeyException`, `RecoveryAccessException`, `RecoveryConflictException`, `RegistrationConflictException`, `RegistrationNotReadyException`, `RegistrationRateLimitException`, `RegistrationUnavailableException`, `RegistrationValidationException` |
| `registration/grpc` | `ProfileCreationClient`, `WalletCreationClient` |
| `registration/model` | `RegistrationStartResult`, `ValidatedRegistration` |
| `registration/repository` | `RecoveryAccessRepository`, `RegistrationLeaseRepository`, `RegistrationOperationRepository`, `RegistrationReadinessRepository`, `RegistrationRecoveryRepository`, `RegistrationSessionRepository`, `RoleRepository`, `UserCredentialRepository`, `UserRepository`, `UserRoleRepository` |
| `registration/security` | `RecoveryAccessVerifier`, `RecoveryAuthenticationFilter` |
| `registration/service` | `RegistrationFacade`, `RegistrationInputValidator`, `RegistrationCompletionWaiter`, `RegistrationRecoveryService`, `RegistrationService`, `RegistrationSessionService`, `RegistrationTokenService`, `RegistrationCreationService` |
| `registration/validation` | `IdempotencyKeyValidator` |
| `registration/web` | `ClientIpAddressResolver`, `RegistrationRateLimitConfiguration`, `RegistrationRateLimiter` |
| `registration/worker` | `ProfileCreationProcessor`, `ProfileCreationScheduler`, `WalletCreationProcessor`, `WalletCreationScheduler`, `RegistrationTaskRunner` |
| `security/config` | `AccessTokenConfiguration`, `Argon2PasswordProperties`, `PasswordHashingConfiguration`, `SecurityConfiguration` |
| `security/controller` | `JwksController` |
| `security/jwt` | `AccessTokenSigner`, `PublicJwtKeys` |
| `session/controller` | `PendingLoginController`, `RefreshController` |
| `session/dto` | `LoginTokenResponse`, `RefreshRequest`, `RefreshResponse` |
| `session/grpc` | `SecureSessionGrpcServer`, `SessionCallerAuthorization`, `SessionVerificationGrpcService` |
| `session/repository` | `PendingLoginRepository`, `RefreshTokenRepository`, `SessionVerificationRepository` |
| `session/service` | `PendingLoginService`, `RefreshTokenService` |
| `shared/exception` | `LoginCredentialsChangedException` |
| `shared/web` | `ApiErrorResponse`, `ApiExceptionHandler`, `RequestIdFilter`, `RequestIdResolver` |

## Основные зависимости по импортам

Стрелка показывает импорт хотя бы одного Java-класса из другого пакета верхнего уровня;
это не означает, что существует циклическая зависимость Spring bean или что все классы связаны.

| Откуда | Куда | Примеры классов |
| --- | --- | --- |
| `registration` | `security` | `RecoveryAccessVerifier`, `RegistrationTokenService` |
| `registration` | `session` | `RecoveryAccessVerifier` |
| `registration` | `shared` | `RecoveryAuthenticationFilter`, `RegistrationSessionService` |
| `security` | `registration` | `SecurityConfiguration` |
| `session` | `registration` | `PendingLoginController`, `PendingLoginRepository`, `PendingLoginService` |
| `session` | `security` | `RefreshTokenService` |
| `session` | `shared` | `PendingLoginController`, `PendingLoginService`, `RefreshController` |
| `shared` | `registration` | `ApiExceptionHandler` |

## Принятые границы

- `registration`: регистрация и восстановление, их HTTP API, DTO, проверка данных,
  запись данных и фоновые gRPC-вызовы. По выбору владельца проекта общие сущности
  пользователя, credentials и ролей и их репозитории также лежат здесь. Вход
  использует некоторые из них; это осознанная зависимость.
- `session`: вход, refresh, проверка сессий и внутренний gRPC-сервер проверки.
- `security`: конфигурация Spring Security, хеширование паролей, подпись JWT и
  публикация публичных ключей. Это техническая безопасность Identity, не отдельный сервис.
- `registration/security`: доступ к операторскому восстановлению регистрации,
  включая проверку сессии. Сценарий восстановления не создаёт вложенных слоёв.
- `shared`: общие технические HTTP-компоненты и общее исключение Identity.

## Зависимости, требующие внимания

- `session` импортирует данные и сервисы `registration` для входа. Удобство
  этого расположения нужно оценивать при следующих изменениях; перенос сам по себе
  не является задачей без согласованного решения.
- `registration` и `security` импортируют классы друг друга: конфигурация
  безопасности подключает фильтр восстановления, а проверка восстановления
  использует общие JWT-компоненты. Это взаимная зависимость пакетов, а не
  доказанный цикл bean. `registration` и `session` также взаимно связаны:
  восстановление проверяет сессию, а вход использует компоненты регистрации.
- `shared/web/ApiExceptionHandler` импортирует исключения регистрации, а
  регистрация использует общие web-компоненты. Это взаимные зависимости
  пакетов. Они пока не объявлены исправленными; при дальнейшем рефакторинге
  нужно проверить влияние на HTTP-контракты и Spring configuration.
- Анализ основан на путях классов и Java imports. Он не является автоматической
  проверкой циклов всех зависимостей, рефлексии или связей Spring во время запуска.
- Проверка графа прямых импортов между Java-классами Identity после возврата
  сущностей в `registration` не обнаружила циклов. Это не отменяет взаимных
  зависимостей пакетов, указанных выше.
