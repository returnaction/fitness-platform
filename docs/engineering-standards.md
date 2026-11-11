# Инженерные стандарты Java-сервисов

> **Статус:** принято
> **Область:** все будущие Java / Spring Boot сервисы
> **Связанные документы:** [конституция разработки](../specs/constitution.md), [спецификация регистрации](../specs/001-user-registration/spec.md), [родительский Maven POM](../pom.xml)

Этот документ задаёт единый способ писать сервисы. Он нужен, чтобы микросервисы не получились разными проектами с разным
стилем, библиотеками и обработкой ошибок.

Примеры ниже служат иллюстрациями. Правила документа не принадлежат отдельному
сервису и одинаково применяются к каждому новому сервису.

## 1. Базовый стек

| Назначение | Решение | Управление версией |
| --- | --- | --- |
| Язык и framework | Eclipse Temurin JDK 25.0.5, Spring Boot 4.1.2 | корневой `pom.xml` |
| HTTP API | Spring MVC (`spring-boot-starter-web`) | Spring Boot BOM |
| Безопасность | Spring Security | Spring Boot BOM |
| Доступ к данным | Spring Data JPA и Hibernate | Spring Boot BOM |
| PostgreSQL | PostgreSQL JDBC driver | Spring Boot BOM |
| Миграции | Liquibase | Spring Boot BOM |
| Валидация | Jakarta Validation | Spring Boot BOM |
| Маппинг DTO / entity | MapStruct 1.6.3 | корневой `pom.xml` |
| Сокращение шаблонного Java-кода | Lombok 1.18.48 | корневой `pom.xml` |
| Unit-тесты | JUnit 5, Spring Boot Test | Spring Boot BOM |
| Интеграционные тесты БД | Testcontainers PostgreSQL | Spring Boot BOM |

Lombok используется для сокращения шаблонного кода. Неизменяемые DTO также
можно оформлять через Java `record`. При применении Lombok к JPA-сущностям
проверяем конструкторы, `equals` и `hashCode`. Пароли, токены и персональные
данные не должны попадать в сгенерированный `toString()`.

Spring Boot BOM управляет версиями Spring-зависимостей. Версия MapStruct
задаётся только в корневом `pom.xml` через `dependencyManagement`; дочерний
сервис добавляет `org.mapstruct:mapstruct` без тега `<version>`. MapStruct
processor подключён в родительской Maven-конфигурации и создаёт реализацию
mapper при компиляции. Beta-версии не используются.

## 2. Структура сервиса: по функции, с привычными слоями

Используем не одну большую папку `controller/` на весь сервис, а группировку
по продуктовой функции.
Внутри функции остаются знакомые Spring-слои `controller → service → repository`.

```text
services/example-service/
  src/main/java/io/github/returnaction/fitness/example/
    featureone/
      controller/
        FeatureController.java
      service/
        FeatureService.java
      repository/
        ItemRepository.java
      entity/
        item/
          ItemEntity.java
          ItemStatus.java
      dto/
        FeatureRequest.java
        FeatureResponse.java
      mapper/
        FeatureMapper.java
      exception/
      configuration/
      grpc/
    featuretwo/
      controller/
      service/
      repository/
      entity/
      dto/
      mapper/
    security/
    shared/
      web/
        ApiExceptionHandler.java
      exception/
      configuration/
```

Названия `example`, `featureone` и `featuretwo` условные; для реального
сервиса и его Java-пакетов используются имена, отражающие предметную область.
Папки создаются только при необходимости. Если у функции несколько сущностей,
их можно разделить на пакеты внутри `entity/`, размещая статусы и идентификаторы
рядом с соответствующей сущностью. Не создаём второй набор `controller/`,
`service/` и других слоёв внутри вложенного сценария той же функции.

`shared/` содержит только технический код, общий для функций этого **одного**
сервиса. Бизнес-правила конкретной функции туда не переносятся.
Нельзя создавать общую Java-библиотеку бизнес-моделей для разных сервисов:
сервисы взаимодействуют через REST, gRPC или события с явным контрактом.

## 3. Ответственность классов

| Слой | Делает | Не делает |
| --- | --- | --- |
| Controller | Принимает HTTP, вызывает `@Valid`, передаёт DTO в service, возвращает DTO и HTTP-статус | Не содержит бизнес-правила, SQL, работу с JPA entity или `try/catch` для каждого случая |
| Service | Реализует use case, проверяет бизнес-инварианты, определяет транзакцию, вызывает repository и согласованные внешние клиенты | Не формирует HTTP-ответы и не читает чужие таблицы |
| Repository | Читает и сохраняет данные только своей базы | Не вызывает внешние сервисы и не содержит бизнес-сценарий |
| Entity | Отражает схему таблицы своего сервиса и JPA-состояние | Не возвращается из API и не передаётся другому сервису |
| DTO | Явно описывает внешний запрос или ответ | Не является JPA entity и не содержит пароль/секрет в `toString()` |
| Mapper | Явно преобразует совместимые поля DTO и entity | Не нормализует email, не хеширует пароль, не назначает роли и не вызывает сервисы |

### Читаемость кода — Clean Code

- Имена классов, методов и переменных отражают их назначение и предметную
  область. Избегаем сокращений и названий, из которых непонятно действие.
- Метод выполняет одну понятную задачу; сложные условия и глубокую вложенность
  упрощаем, когда это делает сценарий легче читать. Размер метода сам по себе
  не повод разбивать связную логику на множество мелких методов.
- Для Java-типов и статических методов используем обычные `import` и `import static`,
  а не полные имена в теле кода. Исключения: строковые значения конфигурации,
  документация и необходимое разрешение конфликта одинаковых имён; причину
  такого исключения указываем рядом с кодом.
- Контроллеры принимают HTTP-запрос, передают данные дальше и формируют ответ.
  Аутентификация находится в Spring Security, бизнес-правила — в service,
  запросы к БД — в repository, как описано выше и в разделе 7.
- DTO HTTP-запросов и ответов размещаем в пакете `dto` соответствующей
  функции, а не объявляем внутри контроллера. Приватные вспомогательные типы
  выносим в отдельные классы, когда это действительно улучшает структуру.
- Не создаём дополнительный слой, интерфейс или класс только ради шаблона;
  применяем их, когда они упрощают код или разделяют разные обязанности.
- Lombok применяем там, где он сокращает шаблонный код без потери ясности;
  правила для JPA и защиты секретов указаны в разделе 1. Единый формат ошибок
  и ограничения на логи описаны в разделах 4 и 10.

## 4. REST, валидация и ошибки

- Внешний API использует DTO и `@Valid`. Контроллер не возвращает JPA entity.
- Для каждого входного поля правила валидации должны иметь понятный код ошибки.
  Сложные предметные правила проверяются в service, а не только аннотациями DTO.
- Один `ApiExceptionHandler` с `@RestControllerAdvice` преобразует ожидаемые
  ошибки в согласованный JSON:

```json
{
  "error": {
    "code": "validation_error",
    "message": "Проверьте введённые данные",
    "details": [{"field": "email", "code": "invalid_format"}],
    "request_id": "<uuid>"
  }
}
```

- Handler отдельно обрабатывает некорректный JSON (`400`), ошибки валидации
  (`422`), доменные конфликты (`409`), ограничения частоты (`429`) и временную
  недоступность (`503`). Неперехваченное исключение логируется с `request_id`,
  но клиент получает только безопасный `500 internal_error`.
- Если клиент не принимает формат JSON, ответ `406` может не содержать тела;
  конкретное поведение и остальные HTTP-статусы фиксируются в контракте API.
- Коды ошибок стабильны для клиента; новый необязательный field можно добавить,
  но нельзя молча менять смысл существующего кода.

## 5. PostgreSQL, JPA и Liquibase

- Каждый сервис подключается только к своей базе. В локальной разработке это
  отдельные базы `fitness_<service>` в установленном PostgreSQL, не контейнер
  Docker.
- Доступ к данным реализуем через Spring Data JPA и Hibernate: используем
  методы репозиториев, запросы JPQL или native SQL в JPA-репозиториях.
  `JdbcClient` для доступа к данным не используем. При переводе существующего
  кода сохраняем транзакции, порядок блокировок и результаты запросов.
- Драйвер PostgreSQL JDBC необходим Hibernate для подключения к PostgreSQL;
  наличие драйвера не означает использование `JdbcClient` в коде сервиса.
- Изменения таблиц, индексов, constraints и справочных данных выполняются
  только Liquibase changeset-ами, лежащими в модуле сервиса. JPA `ddl-auto`
  не создаёт и не изменяет схему.
- `@Transactional` ставится на методе service, который меняет данные. Сетевой
  вызов не выполняется в открытой транзакции PostgreSQL.
- JPA entity не сериализуются в JSON. Не полагаемся на Lazy-loading после
  выхода из service-метода.
- Ограничения уникальности, внешние ключи внутри одной базы и критичные
  инварианты подтверждаются ограничениями PostgreSQL, а не только проверкой в
  Java-коде.

## 6. MapStruct

Каждый mapper — небольшой интерфейс в папке `mapper/` своей функции. Общие
настройки будут заданы через `@MapperConfig`:

```java
@MapperConfig(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface MapperConfiguration {
}
```

`ReportingPolicy.ERROR` заставляет Maven-сборку остановиться, если поле target
не было сознательно отображено. Для PATCH-операций правило работы с `null`
указывается явно в конкретном mapper; оно не задаётся глобально, потому что
«не передано поле» и «очистить поле» имеют разный бизнес-смысл.

## 7. Spring Security

- Каждый HTTP-сервис имеет явный `SecurityFilterChain`; небезопасных
  «разрешить всё» конфигураций нет.
- Только публичные маршруты, например регистрация и login, получают `permitAll`.
  Все остальные маршруты явно требуют аутентификации и нужного права.
- Внешнее API использует stateless Bearer access token. Серверная HTTP-сессия
  для API не используется.
- Разбор Bearer-заголовка и проверка подписи и срока токена выполняются в
  цепочке Spring Security до контроллера. Контроллер получает проверенного
  пользователя через `@AuthenticationPrincipal` и передаёт его сервису.
  Доступность пользователя, актуальность сессии и бизнес-права дополнительно
  проверяются там, где этого требует сценарий; при изменении состояния сессия
  перепроверяется внутри транзакции после необходимых блокировок.
- CSRF отключается только для stateless Bearer API и фиксируется в
  `SecurityConfiguration`; если когда-либо появятся browser cookies, правило
  пересматривается отдельной спецификацией.
- Пароли сравнивает единственная конфигурация Argon2id из спецификации
  регистрации. Сырые пароли, токены и заголовки авторизации не попадают в логи.
- Внутренний gRPC использует mTLS и правила service identity из спецификации;
  он не открывается в интернет.

## 8. Проверка

Тесты сервиса находятся в `src/test/java`, их ресурсы — в
`src/test/resources`. Пакеты тестов повторяют функции и слои основного кода:
тест отдельного класса располагается в пакете соответствующего класса, а тест
сценария, который охватывает несколько слоёв, — в общем пакете функции.
Если тест относится к нескольким функциям сервиса или к его настройке в целом,
его можно разместить в общем пакете сервиса. Тестам не требуется отдельная
папка только из-за их типа.

Имена тестов заканчиваются на `Test`; для интеграционного сценария используем
более понятное окончание `IntegrationTest`. Название само по себе не меняет
способ запуска: в текущей Maven-конфигурации оба варианта запускаются на этапе
`test` и входят в `verify`. Условия отдельных тестов могут предусматривать
пропуск, например при отсутствии локальных сертификатов. Приватные типы не
делаются доступнее только ради размещения тестов.

- Unit-тесты проверяют бизнес-правила, ошибки и права без поднятия Spring
  context, где это возможно.
- Для миграций, уникальных ограничений, транзакций и гонок используются
  интеграционные тесты с настоящим PostgreSQL через Testcontainers. H2 не
  заменяет PostgreSQL-тесты.
- Для REST проверяются успешный путь, validation, security и формат ошибок.
- Для gRPC и событий добавляются контрактные тесты; для важного сквозного пути
  добавляется integration test между сервисами.
- `./mvnw verify` и проверка Markdown-ссылок должны завершаться успешно до PR.

## 9. Форматирование и статический анализ

Для Java-кода применяется **Spotless 3.10.4** с Eclipse Java Formatter 4.37.
Правила хранятся в [конфигурации форматтера](../config/eclipse-java-formatter.xml):
четыре пробела, выравнивание перенесённых параметров и сохранение переносов и
пустых строк. После открывающей фигурной скобки непустого класса должна быть
одна пустая строка перед первым объявлением, включая его комментарии и аннотации.
Это правило задаётся `blank_lines_before_first_class_body_declaration=1`:
`spotless:apply` добавляет строку автоматически, а `spotless:check` и `verify`
проверяют её наличие. Пустые строки после открытия метода добавляются вручную:
форматтер сохраняет их, но не требует автоматически. Spotless также удаляет
неиспользуемые imports и запрещает wildcard imports. Настройки форматирования
IntelliJ необходимо согласовать с этим файлом; при расхождении проверка Maven
использует именно конфигурацию Spotless.

```text
./mvnw spotless:apply   # исправить формат локально
./mvnw spotless:check   # только проверить формат
```

В IntelliJ IDEA профиль можно импортировать через **Settings → Editor → Code
Style → Java → Show Scheme Actions → Import Scheme → Eclipse XML Profile**,
выбрав `config/eclipse-java-formatter.xml`. Встроенный форматтер IntelliJ может
в деталях отличаться от Eclipse; окончательную проверку выполняет Spotless.

**SpotBugs 4.10.5.0** — не formatter. Он анализирует скомпилированный код и
ищет потенциальные ошибки: некорректную работу с `null`, ресурсы, сравнения и
другие типичные дефекты. Используется режим `effort=Max`, `threshold=Medium`:
сборка падает на значимых найденных проблемах, но не блокируется каждым
низкоуверенным предупреждением.

Checkstyle не используется: он в основном контролирует стиль и дублировал бы
Spotless. Все три действия — компиляция, `spotless:check` и `spotbugs:check` —
привязаны к Maven-фазе `verify`; существующий GitHub Actions workflow запускает
их одной командой `./mvnw verify`.

## 10. Логи, health-check и метрики

### Structured logging

- Локальный профиль использует обычный читаемый консольный лог.
- Production-профиль использует встроенный Spring Boot JSON-формат **ECS**:
  `logging.structured.format.console=ecs`. Дополнительная библиотека для этого
  не нужна.
- Каждый HTTP-запрос получает `request_id`: если клиент прислал корректный UUID
  в `X-Request-Id`, сервис его использует; иначе генерирует новый UUID. Значение
  возвращается в response header и кладётся в MDC на время запроса.
- В логах допустимы уровень, имя сервиса, `request_id`, безопасный код ошибки,
  технический ID операции и время. Запрещены пароль, JWT/refresh token,
  `Idempotency-Key`, email, баланс, сертификаты и содержимое HTTP body.
- Logs — не метрики. Позже Grafana сможет показывать JSON-логи через Loki;
  сейчас Loki не разворачивается и не добавляется как зависимость сервисов.

### Spring Boot Actuator

Каждый HTTP-сервис включает `spring-boot-starter-actuator`. Разрешено открыть
только `health`, `info` и `prometheus`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
```

Используем endpoint’ы `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`, `/actuator/info` и `/actuator/prometheus`.
Liveness отражает внутреннее состояние процесса и не должен зависеть от
PostgreSQL, Kafka или сети: иначе инфраструктура будет перезапускать здоровый
процесс при временном внешнем сбое. Readiness может учитывать обязательные для
работы зависимости. Endpoint’ы `env`, `configprops`, `heapdump`, `loggers` и
`mappings` не открываются. На сервере Prometheus получает доступ к management
endpoint только по частной сети; Caddy не публикует его в интернет.

### Prometheus и Grafana

Каждый HTTP-сервис добавляет `micrometer-registry-prometheus` без своей версии:
ею управляет Spring Boot BOM. Эта зависимость вместе с Actuator публикует
метрики для Prometheus на `/actuator/prometheus`.

```text
Spring Boot services
       │  /actuator/prometheus
       ▼
  Prometheus (собирает числовые метрики)
       ▼
  Grafana (строит дашборды и alerts)
```

Автоматически наблюдаем HTTP, JVM, GC, память, threads и Hikari connection
pool. Добавляем пользовательские метрики только для действий, важных для
сервиса: например, количество попыток регистрации по `outcome`, число
`PENDING`-операций, повторов worker и длительность/статусы gRPC-вызовов.
Нельзя использовать в metric labels email, `user_id`, `request_id`, UUID
операции или необработанный URL: это создаёт неограниченную cardinality и
перегружает Prometheus.

Prometheus, Grafana и первые dashboards появятся в ветке локальной платформы,
когда будет хотя бы один сервис, отдающий реальные метрики. До этого добавлять
пустой Docker Compose не нужно.

## 11. Checklist Pull Request

- [ ] Новая библиотека действительно нужна, её версия централизована или
  управляется Spring Boot BOM.
- [ ] Новая схема PostgreSQL имеет Liquibase changeset и проверку миграции.
- [ ] Controller не возвращает entity; DTO и error code документированы.
- [ ] Нет секретов, токенов, паролей или персональных данных в логах.
- [ ] Внешний вызов не выполняется в открытой DB-транзакции.
- [ ] Тесты покрывают успешный сценарий, ключевые ошибки, права и гонки.
- [ ] `./mvnw verify` прошёл: тесты, Spotless и SpotBugs не нашли проблем.
- [ ] Контракты и связанная спецификация обновлены.

## 12. Шаблон Maven-модуля сервиса

Когда создаётся первый сервис, например `services/identity-service`, его POM
наследует корневой POM. Он не повторяет Java, Spring Boot, MapStruct и версии
управляемых библиотек.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.github.returnaction.fitness</groupId>
        <artifactId>fitness-platform</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>identity-service</artifactId>
    <name>Identity Service</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.liquibase</groupId>
            <artifactId>liquibase-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`spring-boot-starter-security`, gRPC, Kafka, RabbitMQ и библиотека для выпуска
JWT добавляются только в том сервисе и той спецификации, где они действительно
нужны. Это предотвращает пустые зависимости «на будущее». Для Identity Spring
Security понадобится при реализации регистрации; точный способ выпуска JWT
будет закреплён вместе с реализацией сессий, а не подменён случайной библиотекой
в baseline.

## 13. Путь HTTP-запроса на примере регистрации

```text
POST /api/v1/auth/register
        │
        ▼
RegistrationController
  └─ @Valid проверяет форму запроса
        │
        ▼
RegistrationService
  ├─ нормализует и проверяет бизнес-правила email
  ├─ кодирует пароль через Argon2id
  ├─ создаёт PENDING пользователя и операцию в транзакции
  └─ запускает дальнейшую обработку регистрации
        │
        ▼
UserRepository / RegistrationOperationRepository
  └─ сохраняют entity только в fitness_identity
        │
        ▼
RegisterResponse
  └─ возвращается клиенту; JPA entity наружу не выходит

Любая ожидаемая ошибка
        │
        ▼
ApiExceptionHandler
  └─ возвращает единый JSON error + request_id
```

`RegistrationWorker` — отдельный service-класс той же функции. Он выбирает
незавершённые операции из PostgreSQL, завершает короткую транзакцию и только
после этого вызывает Accounts и Coins по gRPC. Поэтому сетевой сбой не держит
блокировку базы и не нарушает правило `service → repository`.

## 14. Конфигурация и секреты

В Git хранятся только безопасные значения по умолчанию и имена переменных.
Настоящие пароли, private key, сертификаты, токены и адреса production не
коммитятся.

Каждый сервис использует одинаковые Spring Profiles:

| Файл | Когда применяется | Что содержит |
| --- | --- | --- |
| `application.yaml` | Всегда | Общие безопасные настройки: имя приложения, Liquibase, Actuator, логирование и ссылки на переменные окружения. |
| `application-local.yaml` | `SPRING_PROFILES_ACTIVE=local` | Настройки компьютера разработчика: локальные порты и адресы локальных зависимостей. Секреты по-прежнему приходят из окружения. |
| `application-production.yaml` | `SPRING_PROFILES_ACTIVE=production` | Настройки сервера: production-порты, адреса зависимостей, уровни логов и proxy-настройки без секретов. |
| `application-test.yaml` | Автоматические тесты при необходимости | Только тестовые настройки; адрес PostgreSQL обычно передаёт Testcontainers. |

`application-server.yaml` не является специальным именем Spring Boot и не
используется. Имена `local`, `production` и `test` обязательны для всех
сервисов. Если профиль не указан, разработческий запуск использует `local`.
Сервер обязан явно задавать `production` вне кода и Git:

```text
# environment сервера
SPRING_PROFILES_ACTIVE=production
```

```yaml
# application.yaml: пример структуры, а не готовые credentials
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

server:
  port: ${SERVER_PORT}
```

- `.env.example` — закоммиченный шаблон без настоящих секретов. Его копируют в
  `.env` при первоначальной настройке, но приложение не загружает его при запуске.
  Локальный профиль импортирует `.env`; этот файл нельзя добавлять в Git или image.
- Комментарии в `.env` и `.env.example` пишутся на русском языке; названия
  переменных, файлов и общепринятые технические сокращения остаются без перевода.
- Интеграционные тесты запускаются с профилем `test`. Его
  `src/test/resources/application-test.yaml` импортирует отдельные безопасные
  `test-environment.properties`, а не `.env` или `.env.example`; Testcontainers
  подставляет временные URL, логин и пароль PostgreSQL. Сквозные тесты используют
  эти же тестовые значения и временные базы и ключи.
- При добавлении обязательной переменной обновляются одновременно
  `application*.yaml`, `.env.example` и при необходимости тестовая конфигурация.
  Существующие локальные `.env` обновляются отдельно с сохранением секретов.
  Подробнее: [настройки окружения](environment-configuration.md).
- Production не читает `.env`: Docker Compose или сервер передаёт переменные
  окружения/секреты при запуске контейнера. Dockerfile не копирует `.env` в
  image благодаря `.dockerignore`.
- `ddl-auto: validate` разрешает JPA проверить мигрированную схему, но не
  создавать и не менять её. Единственным источником изменения схемы остаётся
  Liquibase.
- Значения, специфичные для функции — например лимиты регистрации, адрес
  Accounts или параметры worker — сначала описываются в `plan.md` этой функции,
  затем попадают в подходящий `application-<profile>.yaml` через properties и
  переменные окружения.
- Каждый сервис получает порт только через обязательную переменную окружения
  `SERVER_PORT`; значения по умолчанию для порта не используются. Так несколько
  сервисов не смогут случайно занять один и тот же порт.
- Конфигурация worker, rate limit и адреса внутренних сервисов также приходят
  через properties/переменные окружения, а не вшиваются в Java-код.

## 15. Минимальный набор файлов любого нового сервиса

Первый PR, создающий **любой** новый сервис, содержит только нужную основу, а
не всю бизнес-функцию целиком:

```text
services/<service-name>/
  pom.xml
  Dockerfile
  src/main/java/.../<ServiceName>Application.java
  src/main/resources/application.yaml
  src/main/resources/db/changelog/db.changelog-master.yaml
  src/main/resources/db/changelog/changes/001-initial-schema.yaml
  src/test/java/...
```

Если сервис владеет PostgreSQL-данными, первый Liquibase changeset создаёт его
минимальную схему. `Actuator` добавляется вместе с HTTP-сервисом для
health-check. Затем отдельные небольшие PR добавляют только нужные данному
сервису security, REST/gRPC-контракт, persistence, внешних клиентов и фоновые
обработчики. Такой порядок позволяет после каждого шага собрать, запустить и
проверить сервис.
