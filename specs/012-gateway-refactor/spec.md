# Рефакторинг Gateway: централизованная проверка JWT и Token Relay

> **Статус:** completed (выполнена, проверена локально; merge выполняет пользователь)<br>
> **Номер:** 012  
> **Связанные документы:** [Spec 009](../009-gateway-access/spec.md), [сессии Identity](../../system-design/services/identity/31-identity-login-sessions.md)  
> **Заменяет / заменена:** новая реализация Gateway по Spec 012 заменяет старый код Spec 009; переход принимающих сервисов на pre-authentication остаётся отдельной задачей.

Реализация и результаты проверок зафиксированы в [tasks.md](tasks.md) и
[technical-review.md](technical-review.md). Команды запуска и ограничения текущего
Tomcat HTTP/1.1-адаптера описаны в [README Gateway](../../services/gateway-service/README.md).
Завершение зафиксировано 2026-11-12. Отправка ветки разрешена пользователем;
merge и развёртывание не входят в эту отметку завершения.

## Локальные коммиты при выполнении Spec 012

После выполнения каждой самостоятельной задачи или подзадачи:

1. Выполнить относящиеся к ней проверки и записать результат в tasks.md.
2. Проверить diff и включить только изменения этой задачи, необходимые тесты
   и документацию; чужие и несвязанные изменения не добавлять.
3. Создать локальный коммит с идентификатором задачи в сообщении.
4. Только после успешного коммита переходить к следующей задаче.

Повторное разрешение на каждый такой коммит не требуется: пользователь его дал.
Push не выполнять. Merge, выпуск и миграции пользовательских БД этим правилом
не разрешены. Частично выполненную задачу не отмечать завершённой; блокеры
записывать явно. Пустые коммиты для сводных T003/T005 не создавать.
Начало реализации по-прежнему требует отдельной команды пользователя.

## Цель

Основная задача, подтверждённая пользователем:

1. Клиент отправляет запрос в Gateway.
2. Gateway разрешает только описанные маршруты.
3. Для публичных операций пропускает проверку токена.
4. Для защищённых — проверяет JWT и активность сессии.
5. Если проверка успешна — передаёт запрос и исходный токен дальше. Если нет — возвращает ошибку.
6. Бизнес-правила выполняет принимающий сервис.

Передача исходного проверенного токена в пункте 5 относится к защищённым операциям.
На публичных операциях действует согласованная политика: JWT и CheckSession
пропускаются, Authorization перед передачей в Identity удаляется.

Остальные разделы описывают техническую реализацию этой задачи в согласованных
границах. Они не являются основанием расширять функциональность Gateway.

## Границы

### Замена старой реализации gateway-service

**Существующий код `services/gateway-service` — старая реализация, которую необходимо
удалить при начале реализации Spec 012. Новый Gateway пишется заново по этой спецификации.**

- Старые классы, фильтры, маршруты, настройки и тесты не являются источником требований
  и не используются как образец архитектуры или поведения. Не переносить их логику
  только потому, что она присутствует в старом проекте.
- В начале этапа Э02 удалить исходники старой реализации и её тесты внутри модуля
  `services/gateway-service`; новые исходники и тесты создавать по Spec 012.
  Конфигурацию приложения сформировать по spec, без автоматического наследования
  старых параметров. Устаревшие ресурсы старой реализации убрать.
- Сохранить сам модуль и его подключение к общей сборке; Maven Wrapper, общие
  инженерные проверки и зависимости привести к требованиям новой реализации.
  Удаление других сервисов, общих контрактов и пользовательских секретов не входит
  в эту замену.
- Перед удалением проверить Git diff и сохранить имеющиеся незакоммиченные изменения,
  чтобы замена старой реализации не уничтожила чужую незавершённую работу.
- Единственный источник требований к новому поведению Gateway — Spec 012.
  План и задачи определяют порядок её выполнения. При пробеле уточнить spec,
  а не восстанавливать требование по старому коду.
- Упоминания ниже «существующей политики», старых классов и файлов не означают
  разрешения переиспользовать старую реализацию. Согласованные маршруты и внешние
  контракты сохраняются потому, что они явно описаны в spec.

Это инструкция для предстоящей реализации. Текущее изменение документации не
запускает удаление кода; реализация начинается после отдельной команды пользователя.
### Согласованный объём текущей работы

В Spec 012 рефакторится только Gateway с сохранением существующих POST-маршрутов Identity:
`/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`,
`/api/v1/auth/logout-all`. Прямых маршрутов Gateway → Accounts сейчас нет; Accounts в этой работе не
меняется. При подключении его API отдельной работой добавляются маршруты Gateway и
pre-authentication в Accounts. Это относится и к остальным будущим сервисам.

Token Relay реализован в Gateway; создание SecurityContext из claims в принимающих
сервисах остаётся целевой архитектурой. Описанные ниже
обязанности принимающих сервисов — правило будущего подключения, а не требование реализовать их в
Spec 012. Собственная проверка Identity для logout-all сохраняется до отдельного
перехода Identity на pre-authentication после проверки защиты от обхода Gateway.

### Согласованная последующая работа: закрытие внешнего доступа

После завершения Spec 012 отдельной задачей закрыть прямой внешний доступ ко всем микросервисам:
пользовательские запросы извне проходят только через Gateway. Проверить, что внешние подключения
напрямую к портам микросервисов запрещены, а согласованные маршруты через Gateway работают.
Внутренние служебные взаимодействия сохраняются с собственной аутентификацией. Закрытие внешнего
доступа не заменяет проверку доверенного пути от Gateway и защиту от внутреннего обхода перед
включением pre-authentication. Эта работа не входит в Spec 012, не выполнена и не запускается
автоматически.

### Согласованная последующая работа: Identity logout-all

После завершения Spec 012 отдельной задачей перевести `POST /api/v1/auth/logout-all` в Identity на
pre-authentication. Gateway проверяет JWT и активность сессии и передаёт исходный bearer-токен.
Identity на подтверждённом доверенном пути извлекает claims, создаёт `Authentication` в
`SecurityContext` и отзывает все активные сессии пользователя, определённого по `sub`, без повторной
валидации JWT. Бизнес-проверки и изменение состояния сессий остаются ответственностью Identity.

Условие перехода — предварительно выполненная и проверенная отдельная задача защиты доступа к
Identity от обхода Gateway. До этого текущая проверка Identity сохраняется. Проверки refresh/logout
по refresh token и служебный `CheckSession` этим решением не отменяются. Последующая работа не
входит в критерии завершения Spec 012 и не начинается автоматически после её завершения.

### Согласованное отображение ролей в принимающих сервисах

- Идентификатор пользователя в principal берётся из `sub`.
- Названия ролей в JWT сохраняются согласно действующему контракту Identity; это решение не вводит
  новые роли и не меняет выдачу токенов.
- Каждая известная разрешённая роль из `roles` отображается в `GrantedAuthority` с префиксом
  `ROLE_`. Например, если `MODERATOR` предусмотрена контрактом, она отображается в `ROLE_MODERATOR`
  для проверки `hasRole("MODERATOR")`. Этот пример не утверждает наличие роли в текущей реализации
  Identity.
- Неизвестные роли не превращаются в полномочия. Отсутствие/null или пустой список `roles` не выдаёт
  полномочий; роль `USER` по умолчанию не назначается. Некорректный тип claim или элемента списка не
  принимается как корректный контекст.
- Проверки принадлежности данных и бизнес-прав остаются в сервисе-владельце; наличие роли само по
  себе не разрешает доступ к любому объекту.
- Gateway проверяет формат claims и передаёт исходный токен, не преобразуя роли в отдельные
  заголовки и не выполняя бизнес-авторизацию сервисов.

Реализация этого отображения и создание Authentication в принимающих сервисах выполняются отдельной
задачей после завершения Spec 012, при их переводе на pre-authentication и после проверки защиты от
обхода Gateway. Они не входят в критерии завершения Spec 012 и пока не выполнены.

### Входит в эту спецификацию

- Согласованное правило: Token Relay с предварительной аутентификацией (pre-authentication) на
  Gateway.
- Подготовка Gateway к целевой модели; реализация SecurityContext в принимающих сервисах выполняется
  отдельно при подключении их API.
- Разделение проверки токена, доверия к Gateway и бизнес-авторизации.

### Не входит в эту спецификацию

- Изменение Accounts и других принимающих сервисов, подключение новых маршрутов и внедрение в этих
  сервисах pre-authentication.

- Новые бизнес-операции, в том числе создание challenge: это пример применения, а не разрешение
  реализовать новый API.
- Изменение выдачи токенов, ролей, сроков, refresh/logout и отзыва сессий.
- Передача пользовательского контекста в отдельных user/role headers.
- Изменение служебной аутентификации внутренних gRPC-вызовов.

## Клиент текущего выпуска

Клиент — мобильное приложение. Браузерный фронтенд в Spec 012 не поддерживается.
Разрешающие CORS-заголовки не выдаются, исключение для браузерного preflight
не добавляется: OPTIONS обрабатывается по общему правилу методов каталога.
Это не механизм аутентификации и не гарантия запрета запросов из браузерных инструментов.

## Пользователи и права

| Участник | Ответственность |
| --- | --- |
| Фронт | Отправляет пользовательские API-запросы только через Gateway. |
| Gateway | Проверяет access JWT и передаёт проверенный исходный токен. |
| Принимающий сервис | Создаёт Authentication и проверяет роли и бизнес-права. |

## Сценарии

### Защищённый запрос

**Дано:** клиент предъявил допустимый access JWT и прошёл действующие проверки доступа.  
**Когда:** Gateway направляет запрос по разрешённому маршруту.  
**Тогда:** в `Authorization: Bearer <access_token>` передаётся тот же токен. Принимающий сервис
извлекает claims, преобразует principal и роли/права в `Authentication` с `GrantedAuthority` и
помещает его в локальный `SecurityContext`. Проверки методов, например `@PreAuthorize`, и проверки
владельца данных выполняются в сервисе.

### Публичные auth-маршруты: согласованная политика

Для POST `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh` и
`/api/v1/auth/logout` Gateway не извлекает и не проверяет access JWT, не вызывает CheckSession и
удаляет Authorization перед отправкой в Identity. Правило действует независимо от отсутствия,
валидности, истечения или некорректного формата предъявленного bearer-токена. Identity проверяет
данные регистрации, логин/пароль либо refresh token по контракту операции. Например, просроченный
access token не мешает refresh с действующим refresh token. Это не отменяет ошибки Identity при
неверных данных или refresh token.

Для POST `/api/v1/auth/logout-all` обязательны проверки JWT и сессии; проверенный исходный
Authorization передаётся без изменения. Исключение применяется строго по методу и пути из
RouteAccessPolicy. На текущем этапе список публичных пользовательских API исчерпывается этими
четырьмя POST-маршрутами. Это не правило для всего префикса `/api/v1/auth/**`. Новые маршруты
автоматически публичными не становятся; неизвестные запрещены. В будущем публичные операции могут
добавляться только явно, по отдельному согласованному контракту с определением обработки
Authorization. Например, восстановление пароля — возможная будущая операция, не часть Spec 012. На
защищённых маршрутах Gateway проверяет JWT и сессию и передаёт исходный токен.

### Ошибки и граничные случаи

| Условие | Ожидаемое поведение |
| --- | --- |
| JWT отсутствует или недействителен на защищённом маршруте | Gateway отклоняет запрос; бизнес-обработчик не вызывается. |
| Сессия отозвана или проверка сессии недоступна | Сохраняется поведение Spec 009: отказ, без пропуска запроса. |
| Запрос пытается обойти доверенный Gateway | Доступ к пользовательскому API сервиса запрещён. |
| Контекст отсутствует или claims невозможно корректно разобрать | Сервис не создаёт аутентифицированного пользователя и не выполняет операцию. |
| Пользователю не хватает прав | Сервис отказывает в доступе, в том числе через method security. |
| Клиент передал user/role headers | Они не используются как источник пользовательской идентичности. |

## Правила

1. Единственный путь фронта к пользовательским API — Gateway. Доверие к переданному токену основано
   на защищённом пути от Gateway, а не на одном лишь наличии заголовка Authorization или адреса во
   внутренней сети.
2. Gateway валидирует подпись, допустимый алгоритм, срок, issuer, audience и обязательные claims
   согласно контракту Identity. Валидация технически требует разбора JWT; «не разбирать» означает не
   создавать отдельные заголовки пользовательского контекста.
3. Gateway не перевыпускает токен и не заменяет его набором user/role headers. Он передаёт именно
   тот access token, который прошёл проверку.
4. Принимающий сервис в этом потоке не повторяет валидацию JWT и не вызывает Identity для повторного
   подтверждения токена. Он разбирает claims и формирует локальный контекст предварительно
   аутентифицированного пользователя.
5. Сервис проверяет корректность структуры необходимых claims; это не повторная криптографическая
   валидация. Контекст не переносится между запросами.
6. Авторизация методов и объектов остаётся в сервисе-владельце.
7. Проверка активности сессии на Gateway из Spec 009 сохраняется. Формулировка «только валидирует
   токен» не отменяет ранее согласованный отзыв сессий.
8. Token Relay здесь обозначает передачу токена, а не обязательное применение конкретного
   одноимённого фильтра Spring Cloud Gateway.

### Согласованный поток и закрытый доступ к сервисам

Закрытие прямого доступа к другим сервисам пользователь выполнит позже отдельной задачей. Эта
инфраструктурная работа не входит в Spec 012; текущая задача не изменяет сетевой доступ или
конфигурацию других сервисов. До выполнения и проверки этой защиты pre-authentication без повторной
валидации JWT в них не включается.

1. Gateway разбирает JWT для проверки подписи, срока и обязательных claims, проверяет активность
   сессии. Claims не передаются отдельными user/role headers.
2. Gateway передаёт неизменённый токен в `Authorization: Bearer <token>`.
3. Принимающий сервис принимает пользовательский HTTP-запрос только от доверенного Gateway. Прямой
   обход запрещён как извне, так и из внутренней сети; одного отсутствия публичного порта
   недостаточно.
4. На этом доверенном пути сервис извлекает claims без повторной проверки подписи JWT, проверяет их
   структуру и создаёт `Authentication` с principal и `GrantedAuthority` в локальном
   `SecurityContext` текущего запроса.
5. Права на операцию и конкретные данные проверяет сервис-владелец.

Внутренние межсервисные вызовы, например gRPC, могут идти напрямую с отдельной служебной
аутентификацией; это не разрешает обход пользовательского HTTP API. Конкретные сетевые ограничения и
идентификацию Gateway выбирают и проверяют при подключении API сервиса, до включения
pre-authentication. mTLS для этого HTTP-пути данным решением не выбран и не объявляется
реализованным.

Это согласованная целевая модель. В Spec 012 меняется только Gateway; настройка принимающих сервисов
и доказательство запрета обхода выполняются отдельной работой при их подключении. Существующие
проверки Identity этим решением не отключаются.

## Общий таймаут запроса — 3 секунды

Согласован единый бюджет обработки запроса в Gateway: **3 секунды** с начала обработки входящего
запроса в цепочке Gateway. В него входят проверка JWT, проверка сессии, получение соединения,
соединение с downstream и ожидание его ответа. Для публичных маршрутов действует тот же бюджет без
проверки JWT и сессии. Это общий deadline, а не по три секунды на каждый этап.

Каждый сетевой этап использует оставшееся время общего бюджета. Например, если проверка сессии и
предыдущая обработка заняли одну секунду, downstream остаётся не более двух секунд. Истечение
бюджета не запускает новый вызов. Сетевые таймауты отдельных этапов не могут продлевать общий
deadline.

При истечении времени обязательной проверки сессии сохраняется 503 без проксирования; при таймауте
ожидания downstream до начала ответа — 504 upstream_timeout в общем формате ошибок с request ID.
Если ответ уже начал передаваться, передача прерывается без дописывания JSON ошибки. Прекратить
ожидание и отменить исходящий вызов, насколько позволяет транспорт; это не гарантирует отмену уже
выполненной операции в downstream. Автоматический повтор изменяющего запроса из-за таймаута не
добавляется.

Три секунды — лимит обработки в Gateway, а не гарантия доставки ответа клиенту за это время через
сеть. Прямая загрузка видео в хранилище в этот бюджет не входит. Shutdown timeout канала не
относится к бюджету запроса.

Примеры ниже передают общий deadline в gRPC. Отмена HTTP-ожидания реализована
и проверена в Э09 на закреплённом Tomcat HTTP/1.1 и Apache HTTP-клиенте. Установка
только response/read timeout в 3 секунды не доказывает соблюдение общего бюджета. Механизм входит в
T002/T003/T005; отдельные лимиты 1/3/1 секунды не приняты.

### Согласованный механизм отмены по общему deadline

- RequestDeadline хранит единый срок завершения запроса: 3 секунды от входа в Gateway.
- gRPC получает тот же абсолютный deadline без нового отсчёта.
- HTTP-вызов получает оставшееся время общего бюджета.
- Один общий планировщик на экземпляр Gateway регистрирует отмену активного
  сетевого вызова на момент общего deadline. Отдельные планировщики и пулы
  по downstream не создаются.
- Если вызов завершился раньше, его запланированная отмена снимается. Регистрация
  остаётся активной до завершения чтения/закрытия ответа, а не только до получения
  заголовков. При ошибке и завершении запроса ресурсы регистрации освобождаются.
- Планировщик только инициирует отмену сетевого вызова, не пишет HTTP-ответ.
  Ошибку преобразует обычный путь обработки запроса через согласованные
  обработчики и ApiErrorResponse. Повторная запись после commit запрещена.
- При гонке завершения с таймаутом отмена безопасна и не затрагивает следующий
  запрос или уже переиспользованное соединение. Если deadline истёк до регистрации,
  новый сетевой вызов не начинается. После регистрации срок проверяется снова.

Общий планировщик и адаптер отмены реализованы; результаты проверки жизненного
цикла, медленного тела и гонок приведены в T003f/T005e. Адаптер зависит от текущего
Tomcat HTTP/1.1; другие контейнеры и HTTP/2 требуют отдельной реализации и проверки.
Планировщик не является rate limiter и не вводит квоты.

## Согласованный порядок обработки пользовательского запроса

1. Создать общий deadline — RequestDeadlineFilter.
2. Создать request ID — RequestIdFilter.
3. Проверить путь и метод по GatewayRouteCatalog — RouteValidationFilter;
   при ошибке вернуть 404/405; затем отклонить query string с 400 без дальнейшей обработки.
4. Проверить JWT на защищённом маршруте — стандартный Bearer-фильтр Spring Security.
5. Проверить активность сессии — SessionVerificationFilter.
6. Проверить разрешение маршрута — правила Spring Security на основе RouteAccessPolicy.
7. Подготовить исходящие заголовки — ForwardedHeadersFilter.
8. Отправить запрос в сервис через общий HTTP-транспорт с оставшимся временем.

На четырёх публичных auth-маршрутах шаги проверки JWT и сессии пропускаются;
исходящий Authorization удаляется. На защищённых маршрутах обе проверки
обязательны, передаётся исходный проверенный токен. Любой отказ прекращает
дальнейшую обработку и использует уже созданный request ID.

Этот порядок применяется после готовности Gateway; до загрузки ключей пользовательский API не обслуживается.
Это порядок прикладных этапов, а не перечень всех внутренних фильтров Spring.
RequestDeadlineFilter и RequestIdFilter выполняются до security-цепочки.
RouteValidationFilter явно подключается перед BearerTokenAuthenticationFilter,
SessionVerificationFilter — после него и до проверки разрешений. Их отдельная
servlet-регистрация отключена, чтобы не было повторного выполнения.
ForwardedHeadersFilter работает в исходящем HTTP-проксировании, а не является
servlet-фильтром. Итоговые заголовки проверяются на стороне тестового downstream.

## Стек и масштабирование Gateway

- Используется Spring Cloud Gateway Server Web MVC с виртуальными потоками.
- Используется общий HTTP-транспорт Gateway без собственных отдельных пулов и квот на каждый
  downstream. Сетевые таймауты сохраняются.
- Resilience4j, bulkhead и semaphore на текущем этапе исключены.
- Gateway проверяет JWT и активность сессии через Identity, затем передаёт запрос с исходным
  bearer-токеном. Проверка сессии не отменяется.
- На текущем этапе работает один экземпляр Gateway; архитектура должна поддерживать запуск
  нескольких экземпляров в будущем.

## Архитектура пакетов и классов Gateway

Gateway остаётся тонким инфраструктурным сервисом без собственной бизнес-модели и базы данных.
Маршрутизацию и HTTP-проксирование выполняет Spring Cloud Gateway. Структура ниже — целевая; будущие
бизнес-маршруты подключаются только после готовности их контрактов, без реализации новых бизнес-API
в рамках этого решения.

```text
services/gateway-service/
├── pom.xml
├── Dockerfile
├── .env.example
├── .env                         # локальный файл, не хранится в Git
└── src/
    ├── main/
    │   ├── java/io/github/returnaction/fitness/gateway/
    │   │   ├── GatewayApplication.java
    │   │   ├── configuration/
    │   │   │   └── properties/
    │   │   │       ├── AccessTokenProperties.java
    │   │   │       ├── SessionVerificationProperties.java
    │   │   │       ├── UpstreamProperties.java
    │   │   │       └── RequestTimeoutProperties.java
    │   │   ├── routing/
    │   │   │   ├── GatewayRoute.java
    │   │   │   ├── GatewayRouteCatalog.java
    │   │   │   ├── RouteAccess.java
    │   │   │   ├── GatewayRoutesConfiguration.java
    │   │   │   ├── RouteAccessPolicy.java
    │   │   │   ├── RouteValidationFilter.java
    │   │   │   └── UpstreamId.java
    │   │   ├── security/
    │   │   │   ├── SecurityConfiguration.java
    │   │   │   ├── jwt/
    │   │   │   │   ├── JwtDecoderConfiguration.java
    │   │   │   │   └── AccessTokenClaimsValidator.java
    │   │   │   └── session/
    │   │   │       ├── SessionVerificationFilter.java
    │   │   │       ├── SessionVerificationClient.java
    │   │   │       ├── GrpcSessionVerificationClient.java
    │   │   │       └── SessionVerificationUnavailableException.java
    │   │   ├── forwarding/
    │   │   │   └── ForwardedHeadersFilter.java
    │   │   ├── request/
    │   │   │   ├── RequestIdFilter.java
    │   │   │   └── RequestIdResolver.java
    │   │   ├── timeout/
    │   │   │   ├── RequestDeadline.java
    │   │   │   └── RequestDeadlineFilter.java
    │   │   └── error/
    │   │       ├── dto/
    │   │       │   └── ApiErrorResponse.java
    │   │       ├── GatewayErrorCode.java
    │   │       ├── GatewayErrorWriter.java
    │   │       ├── GatewayExceptionHandler.java
    │   │       └── SecurityErrorHandler.java
    │   └── resources/
    │       ├── application.yaml
    │       ├── application-local.yaml
    │       └── application-production.yaml
    └── test/
        ├── java/io/github/returnaction/fitness/gateway/
        │   ├── routing/
        │   ├── security/
        │   ├── forwarding/
        │   ├── request/
        │   ├── timeout/
        │   └── error/
        └── resources/
            ├── application-test.yaml
            └── test-environment.properties
```

### Конфигурация окружений

- `application.yaml` — общая конфигурация сервиса.
- `application-local.yaml` — настройки профиля local.
- `application-production.yaml` — настройки профиля production.
- `.env` — локальные значения переменных окружения; файл исключается из Git.
  `.env.example` описывает необходимые переменные без реальных секретов.
  Способ передачи `.env` в окружение процесса задаётся при реализации запуска;
  наличие файла само по себе не означает, что приложение его загрузит.
- `src/test/resources/application-test.yaml` — конфигурация тестового профиля.
- `src/test/resources/test-environment.properties` — тестовые значения окружения,
  без пользовательских секретов и зависимости от локального `.env`.

### Границы ответственности и Clean Code

`GatewayRouteCatalog` — единственный список операций с методом, путём, upstream
и доступом. Маршрутизация и RouteAccessPolicy строятся из него; policy используется
security, проверкой сессии и исходящих заголовков. `UpstreamId` находится рядом с маршрутизацией; сейчас
используется только IDENTITY, новые направления добавляются при подключении.
Интерфейс SessionVerificationClient отделяет проверку сессии от gRPC-транспорта;
интерфейсы для каждого класса не создаются. DTO ошибки расположен в error/dto,
общую сериализацию выполняет GatewayErrorWriter. RequestIdResolver сохраняет
внутренний ID запроса и не использует заголовок клиента.

RequestTimeoutProperties содержит настройку общего бюджета 3 секунды.
RequestDeadline представляет общий срок и остаток времени; RequestDeadlineFilter
устанавливает его в начале обработки. Эти классы сами по себе не обеспечивают
отмену сетевого ожидания: интеграцию с HTTP/gRPC и окончательный состав необходимых
компонентов проверить в T002, без добавления отдельных пулов и квот.
Пустые controller/service/repository, модели БД и общие бизнес-библиотеки Gateway
не требуются. Структура целевая, создание файлов выполняется при реализации.

### Точка входа и маршрутизация

- `GatewayApplication` — только запуск Spring Boot.
- `GatewayRouteCatalog` — единый список method/path/upstream/access.
- `GatewayRoutesConfiguration` — построение проксирования из каталога и проверка дублей.
- `RouteAccessPolicy` — политика, построенная из каталога по пути и HTTP-методу для
  Spring Security и проверки сессии. Login не требует access token, logout-all требует; неизвестные
  маршруты запрещены.

Следующая таблица показывает перспективные направления. В текущую реализацию входят только
перечисленные выше auth-маршруты Identity.

| Направление | Пользовательские операции |
| --- | --- |
| Identity | Регистрация, login, refresh, logout, logout-all. |
| Accounts | Свой профиль, изменение профиля, публичный профиль. |
| Challenge | Создание, публикация, вступление, задания, участники, результаты, апелляции. |
| Coins | Баланс, история, ежедневная награда. |
| Video Proof | Попытка записи, загрузочная сессия, ссылки и подтверждение частей, статус, playback URL. |
| Moderation | Очередь, назначение карточки, решения, рассмотрение апелляций. |
| Exercise Catalog | Категории, упражнения и вариации. |
| Notifications | История, настройки, отметка прочтения, устройства. |

Видео загружается и просматривается через хранилище по ссылкам; Gateway проксирует управляющие API,
а не тяжёлые видеофайлы.

### Безопасность

- `SecurityConfiguration` — servlet-цепочка Spring Security: stateless-режим, JWT-аутентификация,
  политика маршрутов, проверка сессии и обработчики отказов.
- `JwtDecoderConfiguration` — стандартный decoder Spring Security, доверенные ключи, допустимый
  алгоритм, issuer, audience и время действия.
- `AccessTokenClaimsValidator` — дополнительные требования контракта к `sub`, `sid`, `jti`, их
  формату и сроку токена; без собственной реализации криптографической проверки подписи.
- `AccessTokenProperties` — параметры проверки JWT.
- `SessionVerificationClient` — интерфейс проверки сессии, позволяющий проверять фильтр без запуска
  gRPC.
- `GrpcSessionVerificationClient` — реализация через переиспользуемый gRPC-канал с deadline; без
  отдельного ограничителя параллелизма.
- `SessionVerificationFilter` — после JWT-аутентификации получает проверенные идентификаторы,
  проверяет сессию и допускает запрос только при её действительности.
- `SessionVerificationProperties` — адрес Identity, TLS-настройки и shutdown timeout канала. Недействительная
  сессия и недоступность Identity различаются: отказ в аутентификации и временная недоступность
  соответственно.

### Исходящие клиенты

- `UpstreamId` — логические направления, используемые маршрутами.
- `UpstreamProperties` — только адреса downstream-сервисов.
- HTTP-проксирование выполняет общий транспорт Spring Cloud Gateway MVC. Собственные клиенты,
  registry и выделенные пулы на каждый сервис не создаются. Сетевые таймауты настраиваются
  средствами выбранной версии Gateway.
- gRPC-клиент проверки сессии остаётся отдельным транспортом к Identity: переиспользуемый канал и
  deadline, без собственного semaphore.

Resilience4j, bulkhead и собственные ограничения параллелизма сейчас не используются. Возвращаться к
ним следует после появления подтверждённой проблемы и отдельного решения. Это не отменяет внутренних
ограничений транспорта и конечных таймаутов и не означает обещания неограниченной пропускной
способности.

### Заголовки и входящий запрос

- `ForwardedHeadersFilter` — сохранение исходного проверенного bearer-токена для защищённого
  маршрута, исключение поддельных заголовков внутренней идентичности, передача необходимых
  `Content-Type`, `Accept`, `Idempotency-Key` и политика доверия forwarded-заголовкам. Claims не
  преобразуются в `X-User-Id` или `X-Roles`; протокольные заголовки и передачу тела обслуживает
  транспорт Gateway.
- `RequestIdFilter` — идентификатор запроса для диагностики, downstream и ответа.
- `RequestIdResolver` — создание нового UUID независимо от входящего `X-Request-Id`; используется
  фильтром по образцу Identity.

Последний обсуждённый вариант `RequestSizeLimitFilter` не принят и не входит в детализацию текущего
решения.

В Spec 012 прикладное ограничение размера тела запроса на Gateway не вводится.
Собственный фильтр размера и ответ 413 по такому лимиту не добавляются.
Это не отменяет общего deadline и не обещает неограниченных ресурсов транспорта.

Для пяти текущих auth-маршрутов query string не поддерживается: после проверки
пути и метода запрос с query отклоняется с 400 invalid_request в общем DTO,
с request ID и без вызова JWT decoder, CheckSession или downstream.
Неизвестный путь и неверный метод сохраняют 404/405. Токены из query не извлекаются.
Пример RouteValidationFilter ниже включает эту проверку; его применимость проверить в T002.

### Ошибки и наблюдаемость

- `ApiErrorResponse` — единый DTO ошибок, скопированный по структуре из Identity: обёртка `error`,
  поля `code`, `message`, `details`, `request_id`.
- `GatewayErrorWriter` — единая запись ответа об ошибке.
- `GatewayExceptionHandler` — `@RestControllerAdvice` с `@ExceptionHandler` для исключений,
  обрабатываемых механизмом Spring MVC.
- `SecurityErrorHandler` — стандартные обработчики Spring Security для `401` и `403`, использующие
  тот же DTO.
- Ошибки собственных servlet-фильтров до MVC обрабатываются на месте через `GatewayErrorWriter`; они
  не попадают автоматически в advice.
- Используются стандартные HTTP-метрики. Собственный `UpstreamMetrics` для bulkhead и квот не
  создаётся; токены и идентификаторы пользователей в метки не включаются.

Бизнес-ошибки downstream передаются согласно контракту. Проверка прав на конкретный challenge,
профиль или кошелёк остаётся в сервисе-владельце.

## Детализация обсуждённых классов

**Исторические проектные примеры.** Фрагменты ниже сохраняют ход согласования,
не являются точной копией итогового кода и могут содержать прежние пометки
«пока не реализовано». Актуальные исходники — в `services/gateway-service`,
актуальная проверка выполнения требований — в [tasks.md](tasks.md).

Ниже зафиксированы обсуждённые классы от `GatewayApplication` до компонентов заголовков, request ID
и обработки ошибок. Это проект кода, не реализованные и не проверенные сборкой классы. Примеры
зависят от будущих компонентов; они не являются готовым приложением.

Все классы настроек размещаются в `configuration/properties`, отдельно от клиентов и фильтров.
`@ConfigurationPropertiesScan` находит их внутри корневого пакета Gateway; перенос пакета не меняет
конфигурационные префиксы.

### `GatewayApplication`

Точка запуска в корневом пакете; компоненты ищутся во вложенных пакетах.
`@ConfigurationPropertiesScan` обнаруживает классы настроек. Здесь нет маршрутизации, сетевых
вызовов и ручного создания клиентов.

```java
package io.github.returnaction.fitness.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

### Единый каталог маршрутов

Выбран Java-каталог: каждая операция описывается один раз сочетанием метода,
пути, upstream и доступа. Константы путей с двумя независимыми списками и
отдельный каталог YAML не используются. Каждый public-тип ниже — отдельный файл
в пакете routing. Это проект примеров, не проверенная сборкой реализация.

### `routing/GatewayRoute` и `routing/RouteAccess`

```java
package io.github.returnaction.fitness.gateway.routing;

import java.util.Objects;
import org.springframework.http.HttpMethod;

public record GatewayRoute(HttpMethod method, String path, UpstreamId upstream,
        RouteAccess access) {
    public GatewayRoute {
        Objects.requireNonNull(method, "Route method is required");
        Objects.requireNonNull(path, "Route path is required");
        Objects.requireNonNull(upstream, "Route upstream is required");
        Objects.requireNonNull(access, "Route access is required");
        if (path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with a slash");
        }
    }
}
```

```java
package io.github.returnaction.fitness.gateway.routing;

public enum RouteAccess {
    PUBLIC,
    AUTHENTICATED
}
```

PUBLIC означает согласованное исключение из проверки access JWT/сессии и удаление
Authorization. AUTHENTICATED требует обе проверки и передачу исходного токена.
Значения доступа по умолчанию нет. Бизнес-роли в каталоге не задаются.

### `routing/GatewayRouteCatalog`

```java
package io.github.returnaction.fitness.gateway.routing;

import static io.github.returnaction.fitness.gateway.routing.RouteAccess.AUTHENTICATED;
import static io.github.returnaction.fitness.gateway.routing.RouteAccess.PUBLIC;
import static io.github.returnaction.fitness.gateway.routing.UpstreamId.IDENTITY;
import static org.springframework.http.HttpMethod.POST;

import java.util.List;

public final class GatewayRouteCatalog {
    private static final List<GatewayRoute> ROUTES = List.of(
            new GatewayRoute(POST, "/api/v1/auth/register", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/login", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/refresh", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/logout", IDENTITY, PUBLIC),
            new GatewayRoute(POST, "/api/v1/auth/logout-all", IDENTITY, AUTHENTICATED)
    );

    private GatewayRouteCatalog() {
    }

    public static List<GatewayRoute> routes() {
        return ROUTES;
    }
}
```

Список неизменяемый. Каталог не выполняет сетевые вызовы и не требует @Component.
Accounts и остальные направления добавляются только при отдельном подключении.

### `routing/GatewayRoutesConfiguration`

```java
package io.github.returnaction.fitness.gateway.routing;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.method;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import io.github.returnaction.fitness.gateway.configuration.properties.UpstreamProperties;
import java.util.HashSet;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfiguration {
    @Bean
    RouterFunction<ServerResponse> gatewayRoutes(UpstreamProperties upstreams) {
        var routes = GatewayRouteCatalog.routes();
        validateRoutes(routes);
        return routes.stream()
                .map(definition -> createRoute(definition, upstreams))
                .reduce((left, right) -> left.and(right))
                .orElseThrow(() -> new IllegalStateException("Route catalog must not be empty"));
    }

    private RouterFunction<ServerResponse> createRoute(
            GatewayRoute definition, UpstreamProperties upstreams) {
        String routeId = definition.method().name() + " " + definition.path();
        return route(routeId)
                .route(method(definition.method()).and(path(definition.path())), http())
                .before(uri(upstreams.baseUrl(definition.upstream()).toString()))
                .build();
    }

    private void validateRoutes(List<GatewayRoute> routes) {
        var operations = new HashSet<String>();
        for (GatewayRoute definition : routes) {
            String operation = definition.method().name() + " " + definition.path();
            if (!operations.add(operation)) {
                throw new IllegalStateException("Duplicate route: " + operation);
            }
        }
    }
}
```

Пример объединяет отдельные RouterFunction с собственным before(uri), поэтому
назначение относится только к соответствующей операции. При построении всех
маршрутов baseUrl() проверяет наличие адреса каждого используемого upstream;
ошибка останавливает запуск. Пустой каталог и дубли метода/пути запрещены.

Пути не переписываются: /api/v1/auth/login остаётся тем же путём в Identity.
Не открывать весь downstream через /**. Совместимость route(predicate, handler),
композицию маршрутов и изоляцию before-фильтров проверить на закреплённой версии
Gateway MVC. Интеграция общего deadline и HTTP-отмены остаётся задачей T002.

`@Configuration(proxyBeanMethods = false)` подходит, поскольку зависимости
поступают параметрами от Spring и @Bean-методы не вызывают друг друга.

### `routing/RouteAccessPolicy`

Policy строит matcher-ы из каталога и не содержит собственного списка URI.
Конструктор выполняет преобразование, поэтому здесь нужен явный конструктор,
а не Lombok для простого присваивания зависимостей.

```java
package io.github.returnaction.fitness.gateway.routing;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

@Component
public final class RouteAccessPolicy {
    private final RequestMatcher publicRequests;
    private final RequestMatcher protectedRequests;

    public RouteAccessPolicy() {
        publicRequests = matcherFor(RouteAccess.PUBLIC);
        protectedRequests = matcherFor(RouteAccess.AUTHENTICATED);
    }

    public RequestMatcher publicRequests() {
        return publicRequests;
    }

    public RequestMatcher protectedRequests() {
        return protectedRequests;
    }

    public boolean requiresSessionVerification(HttpServletRequest request) {
        return protectedRequests.matches(request);
    }

    private static RequestMatcher matcherFor(RouteAccess access) {
        var paths = withDefaults();
        List<RequestMatcher> matchers = GatewayRouteCatalog.routes().stream()
                .filter(definition -> definition.access() == access)
                .map(definition -> (RequestMatcher) paths.matcher(
                        definition.method(), definition.path()))
                .toList();
        return matchers.isEmpty() ? request -> false : new OrRequestMatcher(matchers);
    }
}
```

SecurityConfiguration, SessionVerificationFilter и ForwardedHeadersFilter
продолжают использовать policy. Неизвестные пути и методы отклоняет RouteValidationFilter с 404/405;
denyAll() остаётся защитой для запросов, не разрешённых политикой.
Текущий каталог содержит только точные пути; при будущем добавлении шаблонов
проверить отсутствие неоднозначных пересечений и одинаковую семантику matching
в маршрутизации и Spring Security. Проверка точных дублей сама по себе не
доказывает отсутствие пересекающихся шаблонов.

### `routing/RouteValidationFilter`: 404 и 405 по каталогу

В пользовательской security-цепочке проверка маршрута выполняется до Bearer-фильтра.
Если путь не найден в каталоге — 404 `not_found`. Если путь найден, но метод
не разрешён — 405 `method_not_allowed` с Allow, вычисленным из каталога.
Ответ использует ApiErrorResponse и созданный Gateway request ID. Эти отказы
не вызывают JWT decoder, CheckSession или downstream, независимо от Bearer клиента.

```java
package io.github.returnaction.fitness.gateway.routing;

import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults;

import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class RouteValidationFilter extends OncePerRequestFilter {
    private final GatewayErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        List<String> allowedMethods = allowedMethods(request);
        if (allowedMethods.isEmpty()) {
            errorWriter.write(response, HttpServletResponse.SC_NOT_FOUND,
                    GatewayErrorCode.NOT_FOUND, RequestIdResolver.getOrCreate(request));
            return;
        }
        if (!allowedMethods.contains(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, String.join(", ", allowedMethods));
            errorWriter.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    GatewayErrorCode.METHOD_NOT_ALLOWED, RequestIdResolver.getOrCreate(request));
            return;
        }
        if (request.getQueryString() != null) {
            errorWriter.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    GatewayErrorCode.INVALID_REQUEST, RequestIdResolver.getOrCreate(request));
            return;
        }
        chain.doFilter(request, response);
    }

    private static List<String> allowedMethods(HttpServletRequest request) {
        var paths = withDefaults();
        return GatewayRouteCatalog.routes().stream()
                .filter(definition -> paths.matcher(definition.path()).matches(request))
                .map(definition -> definition.method().name())
                .distinct()
                .sorted()
                .toList();
    }
}
```

Примеры: POST /api/v1/auth/unknown → 404; GET /api/v1/auth/login → 405,
Allow: POST. HEAD и OPTIONS не получают неявного разрешения: для пользовательского
API действуют методы каталога. Фильтр не заменяет Spring Security firewall:
запрос, отклонённый firewall, обрабатывается его отдельным обработчиком.

Это фильтр пользовательского API; доступ к собственным management-endpoint
Gateway нужно сохранить в отдельной подходящей security-цепочке при реализации.
Он не добавляет management в каталог проксирования. Конкретная конфигурация
management остаётся открытой технической частью T002 и не показана в примере.

Согласовано: после успешной первоначальной загрузки ключей недоступность Identity сама по себе не переводит Gateway в состояние
not ready и не означает сбой его liveness. Локальная неготовность Gateway учитывается
отдельно. Ошибки зависимости диагностируются отдельно; конкретные запросы получают
согласованные ошибки проверки сессии или HTTP upstream. При полном отказе
Identity login и refresh тоже не работают: готовность Gateway не гарантирует
доступность операций Identity. Проверить это раздельно в T010.

### `security/SecurityConfiguration`

Собирает цепочку пользовательского API: проверка пути/метода → запрет query → проверка JWT →
проверка сессии → правила доступа → обработка маршрута.
`oauth2ResourceServer` использует наш decoder для токенов нашего Identity; внешний провайдер или
реализация полного OAuth2-протокола Identity не требуются. Identity создаёт и подписывает JWT,
Gateway проверяет его.

```java
package io.github.returnaction.fitness.gateway.security;

import io.github.returnaction.fitness.gateway.error.SecurityErrorHandler;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import io.github.returnaction.fitness.gateway.routing.RouteValidationFilter;
import io.github.returnaction.fitness.gateway.security.session.SessionVerificationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RouteAccessPolicy accessPolicy,
            JwtDecoder jwtDecoder,
            SessionVerificationFilter sessionFilter,
            RouteValidationFilter routeValidationFilter,
            SecurityErrorHandler errors) throws Exception {
        var bearerTokenResolver = new DefaultBearerTokenResolver();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(request ->
                                accessPolicy.publicRequests().matches(request)
                                        ? null : bearerTokenResolver.resolve(request))
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .addFilterBefore(routeValidationFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(sessionFilter, BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(access -> access
                        .requestMatchers(accessPolicy.publicRequests()).permitAll()
                        .requestMatchers(accessPolicy.protectedRequests()).authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }

    @Bean
    FilterRegistrationBean<RouteValidationFilter> disableStandaloneRouteValidationRegistration(
            RouteValidationFilter routeValidationFilter) {
        var registration = new FilterRegistrationBean<>(routeValidationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<SessionVerificationFilter>
            disableStandaloneSessionFilterRegistration(
                    SessionVerificationFilter sessionFilter) {
        var registration = new FilterRegistrationBean<>(sessionFilter);
        registration.setEnabled(false);
        return registration;
    }
}
```

Предполагается, что будущий `SecurityErrorHandler` реализует `AuthenticationEntryPoint` и
`AccessDeniedHandler`, а `SessionVerificationFilter` является bean. Отдельная servlet-регистрация
фильтра отключена, чтобы он выполнялся только в назначенном месте security chain.

Отключены HTML-вход, HTTP Basic, встроенный logout и сохранение запросов для перенаправления после
входа. `STATELESS` запрещает опираться на HTTP-сессию Gateway, но не отменяет сессии Identity.
Отключение CSRF относится к явной передаче bearer-токена без автоматической cookie-аутентификации;
при смене модели это решение пересматривается.

`permitAll()` само по себе не отключает обработку предъявленного bearer-токена. Поэтому resolver
выше возвращает null для публичных auth-маршрутов до разбора Authorization.
SessionVerificationFilter пропускает их по RouteAccessPolicy, а исходящий фильтр удаляет
Authorization. Пример требует проверки при реализации. Собственные ошибки проверки сессии должны
корректно записываться самим фильтром через общий механизм ошибок: обработчики дальше по цепочке не
перехватывают автоматически исключения раннего фильтра.

### `security/jwt/JwtDecoderConfiguration`

Создаёт decoder с ES256 и ключами из доверенного настроенного JWKS нашего Identity. Закрытый ключ
остаётся в Identity. JWKS URI не берётся из токена. Стандартные проверки issuer, audience и времени
дополняются нашим валидатором.

Прежний пример с `withJwkSetUri(...)` удалён: он не описывает согласованную
однократную загрузку. Контракт конфигурации decoder:

1. Отдельный загрузчик получает и проверяет JWKS до готовности Gateway.
2. Decoder получает неизменяемый опубликованный набор открытых ключей;
   при валидации не выполняет сетевых вызовов, включая неизвестный kid.
3. Проверяет подпись ES256, issuer, audience, даты и AccessTokenClaimsValidator.
4. JwtTimestampValidator с Duration.ZERO и AccessTokenClaimsValidator используют
   один Spring bean Clock. Deadline использует отдельный монотонный отсчёт.

Конкретный API локального источника ключей подтвердить для выбранных версий
в T002a; не подменять этот контракт стандартным обновляющимся JWKS-кешем.

`NimbusJwtDecoder` проверяет подпись, а не только читает токен. `iss` обозначает ожидаемого
издателя, `aud` — получателя; совпадение этих полей не заменяет криптографическую проверку.
`Duration.ZERO` сохраняет строгую проверку времени без допуска на расхождение часов.
`JwtTimestampValidator` проверяет срок по `exp` и `nbf`; обязательность дат, `iat` и максимальный
срок проверяются ниже. Проверка активности сессии не входит в decoder.

Загрузка JWKS при старте и хранение набора здесь не показаны: их необходимо настроить и проверить
при реализации, включая недоступность и ротацию ключей.

### Согласованная загрузка ключей при старте

Gateway один раз получает JWKS из доверенного Identity при старте и хранит
полученный набор в памяти до завершения процесса. Периодического обновления,
TTL кеша и повторного получения ключей при каждом запросе не добавляется.
Это решение заменяет обсуждение просроченного кеша Q3 и предложение TTL 5 минут Q4.

Подпись и claims каждого JWT проверяются локально по загруженным ключам;
CheckSession по-прежнему вызывается для каждого защищённого запроса.
Недействительный JWT даёт 401. При отсутствии загруженных ключей
доступ не предоставляется. При первоначальной загрузке Gateway повторяет запросы
JWKS до получения корректного набора и остаётся неготовым (readiness=false).
Временная недоступность JWKS не завершает процесс ошибкой; общего лимита попыток
нет. Каждая попытка имеет конечный таймаут, между попытками есть пауза;
параллельные попытки не запускаются. Пользовательский трафик до готовности
не обслуживается. Management позволяет наблюдать состояние инициализации;
ожидание ключей само по себе не является сбоем liveness.
После успешной загрузки набор публикуется атомарно, повторы прекращаются,
и при выполнении остальных локальных условий Gateway становится готовым.
Остановка процесса отменяет ожидание и новые попытки. После инициализации
последующий сбой Identity сам по себе не снимает готовность Gateway (Q5).
Удаление или замена ключа в Identity не обнаруживается работающим Gateway
автоматически: для получения нового набора нужен перезапуск. Процедуру плановой
и экстренной замены ключей описать в T009. При неизвестном kid не запускать
автоматическую загрузку JWKS; после успешной загрузки набора возвращать 401 invalid_access_token (Q9 согласован).

Контракт decoder выше не является готовой реализацией этой политики:
в T009 обеспечить проверку по загруженному набору без скрытого сетевого обновления.
### Технический план первоначальной загрузки и смены ключей

- Таймаут одной попытки — 3 секунды на полное получение и разбор ответа;
  пауза после неуспешной попытки — 5 секунд от её завершения. Это отдельные
  настройки startup, не обновление JWKS и не бюджет пользовательского запроса.
  Значения закрепить в конфигурации; попытки последовательны, без общего предела.
- Успех — полностью разобранный набор с хотя бы одним пригодным открытым ключом
  ES256 и однозначным выбором по kid. Непригодный/пустой набор, дубли kid и повреждённый
  ответ не публикуются. Точные правила разбора и поддержку API проверить в T002a.
- При сетевой ошибке или некорректном ответе оставаться not ready и повторять;
  ошибку локальной конфигурации (например, недопустимый URI) выявлять отдельно.
- Диагностика содержит этап, номер попытки, тип отказа и длительность; не содержит
  JWT, содержимого ключей, секретов или тела ответа. Успех загрузки фиксируется один раз.
- Readiness сама по себе не закрывает порт: T002d должен выбрать механизм, который
  запрещает обслуживание пользовательских операций до загрузки, включая прямой доступ,
  и оставляет management доступным. Не считать одну health-проверку доказательством.
- Плановая смена без отказа новых токенов требует предварительной публикации обоих
  открытых ключей Identity: сначала старый+новый набор, затем перезапуск Gateway,
  проверка его готовности, и только затем выдача токенов новым ключом. Старый ключ
  оставляют до истечения старых токенов; после удаления нужен новый перезапуск Gateway.
  Поддержка такой последовательности в Identity не проверялась и не реализуется здесь.
  Если предварительная публикация невозможна, бесшовную смену не обещать.
- При компрометации ключа старому набору нельзя продолжать доверять: остановить
  обслуживание экземпляров с ним, заменить ключ в Identity, загрузить новый набор
  перезапуском и проверить отказ старым токенам. Возможна недоступность; не обещать
  её отсутствия. Выполнение операций — отдельная разрешённая работа, не эта документация.
### `security/jwt/AccessTokenClaimsValidator`

Проверяет обязательные данные уже разобранного decoder-ом JWT. Не выполняет сетевых вызовов и не
подтверждает существование пользователя или сессии.

```java
package io.github.returnaction.fitness.gateway.security.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofMinutes(15);
    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
            "invalid_token",
            "Access token claims do not satisfy the token contract", null);
    private final Clock clock;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        var claims = token.getClaims();
        if (!isCanonicalUuid(claims.get("sub"))
                || !isCanonicalUuid(claims.get("sid"))
                || !isCanonicalUuid(claims.get("jti"))) {
            return invalidToken();
        }
        if (!(claims.get("iat") instanceof Instant issuedAt)
                || !(claims.get("exp") instanceof Instant expiresAt)) {
            return invalidToken();
        }
        Instant now = clock.instant();
        if (issuedAt.isAfter(now)) {
            return invalidToken();
        }
        if (!expiresAt.isAfter(issuedAt)) {
            return invalidToken();
        }
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.compareTo(MAX_TOKEN_LIFETIME) > 0) {
            return invalidToken();
        }
        if (!hasValidRolesFormat(claims.get("roles"))) {
            return invalidToken();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean isCanonicalUuid(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equals(text);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasValidRolesFormat(Object value) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof List<?> roles)) {
            return false;
        }
        return roles.stream().allMatch(role -> role instanceof String);
    }

    private static OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
    }
}
```

- `sub` — пользователь, `sid` — сессия, `jti` — токен; все три обязательны и имеют канонический
  lowercase UUID.
- `iat` и `exp` обязательны и представлены в `Jwt` как `Instant` после преобразования decoder-ом.
  `iat` не в будущем, `exp` позже `iat`, срок не больше 15 минут. Истечение относительно текущего
  времени проверяет подключённый выше `JwtTimestampValidator`.
- Отсутствие/null `roles` не выдаёт полномочий; присутствующее значение должно быть списком строк
  без null. Неизвестная роль не превращается автоматически в разрешение, роль `USER` не назначается
  по умолчанию.
- Один Spring bean `Clock` передаётся и AccessTokenClaimsValidator через конструктор,
  и JwtTimestampValidator через setClock(clock). В тестах оба используют один
  фиксированный Clock. Deadline остаётся монотонным и не зависит от этого Clock.
  `Clock` позволяет тестам фиксировать время. Проверяются отсутствующие и неверно типизированные
  claims, UUID, даты и роли; токены не логируются.

### `configuration/properties/AccessTokenProperties`

Типизированные настройки, загружаемые через `@ConfigurationPropertiesScan`. `record` предоставляет
неизменяемые поля и методы `jwksUri()`, `issuer()`, `audience()`. Отдельный `@Component` не нужен.
Ошибки обязательных настроек должны останавливать запуск.

```java
package io.github.returnaction.fitness.gateway.configuration.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.security.access-token")
public record AccessTokenProperties(
        @NotNull URI jwksUri,
        @NotBlank String issuer,
        @NotBlank String audience) {

    @AssertTrue(message = "JWKS URI must be an absolute HTTP(S) address "
            + "without user information or a fragment")
    public boolean isJwksUriValid() {
        if (jwksUri == null) {
            return true; // Отсутствие проверяет @NotNull.
        }
        boolean supportedScheme = "https".equalsIgnoreCase(jwksUri.getScheme())
                || "http".equalsIgnoreCase(jwksUri.getScheme());
        return supportedScheme
                && jwksUri.getHost() != null
                && jwksUri.getUserInfo() == null
                && jwksUri.getFragment() == null;
    }
}
```

```yaml
gateway:
  security:
    access-token:
      jwks-uri: ${GATEWAY_JWKS_URL}
      issuer: ${GATEWAY_JWT_ISSUER}
      audience: ${GATEWAY_JWT_AUDIENCE}
```

`jwks-uri` — сетевой адрес публичных ключей; `issuer` — идентификатор издателя в claim `iss`, не
обязательно этот адрес; `audience` — ожидаемый получатель в claim `aud`. Значения согласованы с
Identity через конфигурацию окружения. HTTP допускается для локальной среды; production требует
защищённого доверенного канала. Валидация URL не доказывает доверие к серверу. Класс не скачивает
ключи и не проверяет токен. Таймаут и пауза первоначальной загрузки определены в разделе о первоначальной загрузке; TTL и периодического обновления нет.

### `security/session/SessionVerificationClient`

Интерфейс одной операции проверки сессии. Фильтр зависит от него, не от gRPC; в тестах клиент можно
заменить без запуска Identity. Входные `userId` и `sessionId` получены из проверенного JWT, а не из
заголовков пользователя.

```java
package io.github.returnaction.fitness.gateway.security.session;

import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import java.util.UUID;

public interface SessionVerificationClient {
    /**
     * true — Identity подтвердил ACTIVE; false — подтвердил INVALID.
     * Технический отказ завершается исключением, а не false.
     */
    boolean isActive(UUID userId, UUID sessionId, UUID requestId, RequestDeadline deadline);
}
```

| Исход | Результат клиента | Поведение Gateway |
| --- | --- | --- |
| Identity вернул `ACTIVE` | `true` | Продолжить обработку. |
| Identity вернул `INVALID` | `false` | Отказать с `401`. |
| Недоступность, deadline, ошибка обмена или неизвестный статус | `SessionVerificationUnavailableException` | Безопасный `503`, без предоставления доступа. |

Для технических отказов предусмотрен отдельный тип `SessionVerificationUnavailableException` в
пакете `security/session`: наследник `RuntimeException` с конструкторами для сообщения и сообщения с
причиной. Это дополнительный вспомогательный тип к исходному дереву классов. Сообщения и причины
исключения не выдаются клиенту напрямую. Login, refresh и получение профиля не входят в обязанности
этого интерфейса.

### `security/session/GrpcSessionVerificationClient`

Реализация интерфейса через gRPC. Один переиспользуемый `ManagedChannel` управляет соединениями с
Identity и переподключением; это не поток и не обязательно одно TCP-соединение. Stub —
сгенерированный Protobuf-клиент. Blocking stub соответствует выбранному MVC с виртуальными потоками;
сам этот класс потоки не создаёт. Канал и базовый stub создаются один раз.

```java
package io.github.returnaction.fitness.gateway.security.session;

import io.github.returnaction.fitness.contracts.identity.session.v1.CheckSessionRequest;
import io.github.returnaction.fitness.contracts.identity.session.v1.IdentitySessionServiceGrpc;
import io.github.returnaction.fitness.gateway.configuration.properties.SessionVerificationProperties;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;

@Component
public final class GrpcSessionVerificationClient implements SessionVerificationClient {
    private final ManagedChannel channel;
    private final IdentitySessionServiceGrpc.IdentitySessionServiceBlockingStub stub;
    private final SessionVerificationProperties properties;

    public GrpcSessionVerificationClient(SessionVerificationProperties properties)
            throws SSLException {
        this.properties = properties;
        var tls = GrpcSslContexts.forClient()
                .trustManager(properties.trustCertificate().toFile())
                .keyManager(properties.certificate().toFile(), properties.privateKey().toFile())
                .build();
        channel = NettyChannelBuilder.forAddress(properties.host(), properties.port())
                .sslContext(tls)
                .disableRetry()
                .build();
        stub = IdentitySessionServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public boolean isActive(UUID userId, UUID sessionId, UUID requestId, RequestDeadline deadline) {
        if (deadline.expired()) {
            throw new SessionVerificationUnavailableException("Request deadline exceeded");
        }
        try {
            var request = CheckSessionRequest.newBuilder()
                    .setUserId(userId.toString())
                    .setSessionId(sessionId.toString())
                    .setRequestId(requestId.toString())
                    .build();
            var response = stub.withDeadline(deadline.grpcDeadline()).checkSession(request);
            return switch (response.getStatus()) {
                case SESSION_STATUS_ACTIVE -> true;
                case SESSION_STATUS_INVALID -> false;
                default -> throw new SessionVerificationUnavailableException(
                        "Unexpected session verification response");
            };
        } catch (StatusRuntimeException exception) {
            throw new SessionVerificationUnavailableException(
                    "Session verification RPC failed", exception);
        }
    }

    @PreDestroy
    public void close() {
        channel.shutdown();
        try {
            boolean terminated = channel.awaitTermination(
                    properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!terminated) {
                channel.shutdownNow();
            }
        } catch (InterruptedException exception) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

- mTLS: доверенный CA проверяет сервер; сертификат и private key Gateway подтверждают вызывающий
  сервис. Проверка имени сервера не отключается. Пользовательский JWT не заменяет служебную
  идентичность.
- Проверка сессии выполняется без собственного ограничителя параллелизма. Deadline RPC обязательно
  ограничивается общим бюджетом запроса в 3 секунды: пример выше передаёт тот же
  абсолютный deadline в stub без нового отсчёта.

- Автоматические retry отключены. Только явный `INVALID` означает недействительность сессии;
  технический отказ не становится `false` или `true`.
- При остановке сначала выполняется graceful shutdown канала, после ограниченного ожидания —
  принудительное закрытие. Прерывание сохраняется.
- Проверить `ACTIVE`, `INVALID`, неизвестный статус, deadline, недоступность Identity и неверные
  сертификаты. Код пока не проверен сборкой или этими тестами.

### `security/session/SessionVerificationFilter`

Servlet-фильтр после JWT-аутентификации, использующий `RouteAccessPolicy` и интерфейс клиента. Не
разбирает `Authorization` заново и не проверяет подпись повторно; идентификаторы извлекаются из
`JwtAuthenticationToken` в контексте.

```java
package io.github.returnaction.fitness.gateway.security.session;

import io.github.returnaction.fitness.gateway.error.GatewayErrorCode;
import io.github.returnaction.fitness.gateway.error.GatewayErrorWriter;
import io.github.returnaction.fitness.gateway.error.SecurityErrorHandler;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import io.github.returnaction.fitness.gateway.timeout.RequestDeadlineFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class SessionVerificationFilter extends OncePerRequestFilter {
    private final RouteAccessPolicy accessPolicy;
    private final SessionVerificationClient sessionClient;
    private final SecurityErrorHandler securityErrors;
    private final GatewayErrorWriter errorWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !accessPolicy.requiresSessionVerification(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)
                || !jwtAuth.isAuthenticated()) {
            rejectAuthentication(request, response);
            return;
        }
        UUID userId;
        UUID sessionId;
        try {
            var token = jwtAuth.getToken();
            userId = UUID.fromString(token.getSubject());
            sessionId = UUID.fromString(token.getClaimAsString("sid"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            rejectAuthentication(request, response);
            return;
        }
        UUID requestId = requireRequestId(request);
        final boolean active;
        try {
            active = sessionClient.isActive(
                    userId, sessionId, requestId, RequestDeadlineFilter.requireDeadline(request));
        } catch (SessionVerificationUnavailableException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    GatewayErrorCode.IDENTITY_UNAVAILABLE, requestId.toString());
            return;
        }
        if (!active) {
            rejectAuthentication(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void rejectAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        securityErrors.commence(request, response,
                new BadCredentialsException("Invalid access token"));
    }

    private static UUID requireRequestId(HttpServletRequest request) {
        Object value = request.getAttribute("request_id");
        if (!(value instanceof String text)) {
            throw new IllegalStateException(
                    "RequestIdFilter must set request_id as a string before security filters");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "RequestIdFilter must provide a valid request_id", exception);
        }
    }
}
```

`chain.doFilter()` вызывается только после положительной проверки и находится вне `try/catch`
клиента: ошибки дальнейшего проксирования нельзя ошибочно объявлять недоступностью Identity. При
`401`/`503` контекст очищается и запрос не передаётся дальше. Публичные операции пропускают этот
фильтр, неизвестные затем запрещаются security chain. Поведение при async/error dispatch требует
отдельной проверки; `OncePerRequestFilter` не заменяет её.

Предлагаемый `GatewayErrorWriter.write(response, status, error, requestId)` формирует общий
безопасный DTO. Отсутствующий или повреждённый request ID — ошибка конфигурации цепочки Gateway, не
ошибка токена; её обрабатывает общий механизм серверных ошибок. Проверить, что при каждом отказе
следующий обработчик не вызывается.

#### Соглашение о `RequestIdFilter` и `RequestIdResolver`

В Gateway будет свой `request/RequestIdFilter` по образцу существующего [фильтра
Identity](../../services/identity-service/src/main/java/io/github/returnaction/fitness/identity/shared/web/RequestIdFilter.java).
Вместе с ним добавляется `request/RequestIdResolver` по образцу [resolver
Identity](../../services/identity-service/src/main/java/io/github/returnaction/fitness/identity/shared/web/RequestIdResolver.java).
Gateway всегда создаёт новый UUID для каждого входящего запроса. Любое значение X-Request-Id
клиента, включая корректный UUID, игнорируется и заменяется своим ID. Клиентское приложение не
обязано генерировать этот заголовок. Gateway не зависит от Java-классов Identity. Фильтр выполняется
до security, создаёт ID, сохраняет `request_id` как **String**, возвращает `X-Request-Id`,
устанавливает MDC и очищает контекст логирования после обработки. Это уточнение заменяет
первоначальный пример с UUID в атрибуте запроса. Для клиента проверки строка преобразуется в UUID.
При пересылке в Identity сохраняется тот же ID в заголовке: между сервисами передаётся значение, не
объект фильтра. ID не является credential и не предоставляет прав.

Один ID используется в атрибуте запроса, MDC, заголовке ответа и поле `error.request_id`, включая
ошибки аутентификации до проксирования. Передачу того же `X-Request-Id` в исходящем HTTP-запросе
обеспечивает механизм проксирования, а не входящий `RequestIdFilter`. Downstream сохраняет
полученный корректный ID; gRPC-проверка сессии получает тот же ID в поле запроса.

Существующая обработка ID внутри `GatewayFilter` при рефакторинге заменяется этими двумя классами, а
не дублируется. Новое согласованное правило заменяет прежнее сохранение корректного клиентского ID.
Это намеренное изменение внешнего поведения Gateway, которое следует отразить в связанных
контрактах. Проверить генерацию нового ID при отсутствующем, пустом, некорректном и корректном
клиентском значении, замену входящего ID, совпадение ID в HTTP/gRPC, логах, ответе и JSON ошибки,
наличие ID при 401 и очистку MDC.

### `configuration/properties/SessionVerificationProperties`

Типизированные настройки gRPC-проверки сессии, используемые клиентом:

| Метод / поле | Тип | Назначение |
| --- | --- | --- |
| `host()` | `String` | Адрес gRPC Identity, без вшитого адреса в Java. |
| `port()` | `int` | Порт gRPC Identity. |
| `trustCertificate()` | `Path` | Файл доверенного CA для проверки сервера. |
| `certificate()` | `Path` | Файл клиентского сертификата Gateway. |
| `privateKey()` | `Path` | Файл закрытого ключа Gateway. |
| `shutdownTimeout()` | `Duration` | Ограниченное ожидание завершения канала при остановке. |

Параметры приходят из конфигурации окружения; конкретные численные значения этим обсуждением не
утверждены. Проверка обязательных значений, допустимых портов и корректных длительностей выполняется
при запуске. TLS-материалы не хранятся в Git и не выводятся в логи. Класс содержит настройки и их
валидацию, не создаёт канал и не выполняет RPC.

```java
package io.github.returnaction.fitness.gateway.configuration.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.security.session-verification")
public record SessionVerificationProperties(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotNull Path trustCertificate,
        @NotNull Path certificate,
        @NotNull Path privateKey,
        @NotNull Duration shutdownTimeout
) {
    @AssertTrue(message = "Shutdown timeout must be at least one millisecond "
            + "and fit in milliseconds")
    public boolean isShutdownTimeoutValid() {
        if (shutdownTimeout == null) {
            return true;
        }
        try {
            return shutdownTimeout.toMillis() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

}
```

```yaml
gateway:
  security:
    session-verification:
      host: ${IDENTITY_SESSION_HOST}
      port: ${IDENTITY_SESSION_PORT}
      trust-certificate: ${GATEWAY_TRUST_CERTIFICATE}
      certificate: ${GATEWAY_CERTIFICATE}
      private-key: ${GATEWAY_PRIVATE_KEY}
      shutdown-timeout: ${SESSION_SHUTDOWN_TIMEOUT}
```

Имена переменных предложены для будущей конфигурации, а не описывают уже внесённые изменения.
Длительности задаются с единицами, например `ms` или `s`. `host` — имя хоста без схемы URL. `Path`
не проверяет существование и корректность сертификатов: TLS-материалы загружаются при создании
клиента.

`@AssertTrue` требует, чтобы метод вернул `true`: это проверка настроек при создании bean, а не
механизм отсчёта времени на каждом запросе. Отсутствующие значения проверяет `@NotNull`. При обычном
запуске настройки валидируются один раз. Отдельного timeout для RPC нет:
клиент получает общий RequestDeadline запроса. Shutdown timeout относится только
к остановке канала; параметры допуска и semaphore исключены.

### Общий deadline: настройки и передача между этапами

Настройка одна, в общей application.yaml; профили сохраняют согласованное
значение 3s. Проверка properties валидирует длительность, а проверка конфигурации
подтверждает именно 3 секунды во всех профилях:

```yaml
gateway:
  request:
    timeout: 3s
```

```java
package io.github.returnaction.fitness.gateway.configuration.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.request")
public record RequestTimeoutProperties(@NotNull Duration timeout) {
    @AssertTrue(message = "Request timeout must be positive and fit in nanoseconds")
    public boolean isTimeoutValid() {
        if (timeout == null) {
            return true; // Отсутствие проверяет @NotNull.
        }
        try {
            return timeout.toNanos() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
```

RequestDeadline использует уже доступный gRPC Deadline с монотонным отсчётом.
Это один абсолютный deadline на запрос: gRPC получает тот же объект, HTTP —
остаток времени. Новый Deadline.after() при начале RPC не вызывается.

```java
package io.github.returnaction.fitness.gateway.timeout;

import io.grpc.Deadline;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public record RequestDeadline(Deadline grpcDeadline) {
    public static RequestDeadline start(Duration timeout) {
        return new RequestDeadline(Deadline.after(timeout.toNanos(), TimeUnit.NANOSECONDS));
    }

    public boolean expired() {
        return grpcDeadline.isExpired();
    }

    public long remainingNanos() {
        return Math.max(0, grpcDeadline.timeRemaining(TimeUnit.NANOSECONDS));
    }
}
```

```java
package io.github.returnaction.fitness.gateway.timeout;

import io.github.returnaction.fitness.gateway.configuration.properties.RequestTimeoutProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestDeadlineFilter extends OncePerRequestFilter {
    private static final String DEADLINE_ATTRIBUTE = RequestDeadline.class.getName();
    private final RequestTimeoutProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getAttribute(DEADLINE_ATTRIBUTE) == null) {
            request.setAttribute(DEADLINE_ATTRIBUTE, RequestDeadline.start(properties.timeout()));
        }
        chain.doFilter(request, response);
    }

    public static RequestDeadline requireDeadline(HttpServletRequest request) {
        Object value = request.getAttribute(DEADLINE_ATTRIBUTE);
        if (value instanceof RequestDeadline deadline) {
            return deadline;
        }
        throw new IllegalStateException("Request deadline must be initialized before security");
    }
}
```

Порядок: RequestDeadlineFilter → RequestIdFilter → проверка пути/метода → запрет query →
JWT → сессия → разрешение маршрута → исходящие заголовки → HTTP-вызов.
Проверки JWT и сессии выполняются только на защищённых маршрутах.
При повторном dispatch используется тот же атрибут, бюджет не обновляется.
Фильтр выше устанавливает контекст времени, но НЕ является механизмом отмены
всего HTTP-запроса. Требования к интеграции общего HTTP-транспорта в T002:

- До сетевого действия получить remainingNanos(); при нуле не начинать вызов.
- Получение соединения, connect, ожидание заголовков и чтение тела ограничиваются
  оставшимся временем. Нельзя передать ноль в API, где он означает бесконечность.
- Согласованный общий планировщик вызывает отмену активного сетевого запроса
  при общем deadline, даже если данные поступают понемногу.
  Проверка времени только после chain.doFilter() не обеспечивает этот предел.
- Валидация JWT не выполняет сетевое получение JWKS: используется набор первоначальной загрузки.
  До первоначальной загрузки Gateway остаётся неготовым согласно Q8.
- Отмена и запись ошибки должны иметь одного владельца: исключить гонку между
  обычным ответом и таймаутом, отменять регистрацию отмены при завершении запроса.
- Проверить async/error dispatch и очистку контекста. Завершение ожидания Gateway
  не гарантирует отмену бизнес-операции в сервисе.

Конкретный адаптер отмены HTTP пока не показан: его API и применимость к общему
транспорту проверяются в T002. Эти фрагменты — проект документации, а не доказательство
работающего end-to-end deadline. Не добавлять отдельные пулы или квоты ради этого.

### `routing/UpstreamId`

Логический идентификатор downstream-сервиса для маршрутов и настроек адресов. Это не URL, не DNS-имя
и не адрес для фронта.

```java
package io.github.returnaction.fitness.gateway.routing;

public enum UpstreamId {
    IDENTITY
}
```

Enum исключает опечатки в строковых идентификаторах на уровне компиляции. Наличие значения не
создаёт маршрут, клиент или соединение. Настройки и маршруты добавляются отдельно по мере готовности
контрактов сервисов.

### `configuration/properties/UpstreamProperties`

Хранит только адреса downstream. Метод `baseUrl(id)` сохраняет контракт, использованный в примерах
маршрутов. Лимиты соединений, параллелизма и отдельные настройки клиентов на каждый сервис исключены
из этой конфигурации.

```java
package io.github.returnaction.fitness.gateway.configuration.properties;

import io.github.returnaction.fitness.gateway.routing.UpstreamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateway.upstreams")
public record UpstreamProperties(
        @NotEmpty Map<UpstreamId, @NotNull @Valid Settings> services
) {
    public URI baseUrl(UpstreamId upstreamId) {
        Settings settings = services.get(upstreamId);
        if (settings == null) {
            throw new IllegalStateException(
                    "Missing upstream configuration: " + upstreamId);
        }
        return settings.baseUrl();
    }

    public record Settings(@NotNull URI baseUrl) {
        @AssertTrue(message = "Base URL must be an HTTP(S) origin")
        public boolean isBaseUrlValid() {
            if (baseUrl == null) {
                return true;
            }
            String scheme = baseUrl.getScheme();
            String path = baseUrl.getPath();
            return ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))
                    && baseUrl.getHost() != null
                    && baseUrl.getUserInfo() == null
                    && baseUrl.getQuery() == null
                    && baseUrl.getFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && (baseUrl.getPort() == -1
                        || (baseUrl.getPort() >= 1 && baseUrl.getPort() <= 65535));
        }
    }
}
```

```yaml
gateway:
  upstreams:
    services:
      IDENTITY:
        base-url: ${IDENTITY_HTTP_BASE_URL}
```

Отсутствие адреса используемого маршрута должно выявляться на старте. Общий бюджет запроса
согласован: 3 секунды. Имена свойств, семантику транспортных таймаутов и механизм соблюдения общего
deadline проверить по закреплённой версии Gateway MVC; отдельные этапы не получают новый бюджет.

### `forwarding/ForwardedHeadersFilter`

Обрабатывает заголовки исходящего запроса через `RequestHttpHeadersFilter` Gateway MVC. Это не
servlet-фильтр. Сохраняет разрешённые заголовки и задаёт request ID и адрес по принятому правилу; не
меняет тело и не перевыпускает JWT.

```java
package io.github.returnaction.fitness.gateway.forwarding;

import io.github.returnaction.fitness.gateway.request.RequestIdFilter;
import io.github.returnaction.fitness.gateway.routing.RouteAccessPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public final class ForwardedHeadersFilter implements RequestHttpHeadersFilter {
    private final RouteAccessPolicy accessPolicy;

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final List<String> ALLOWED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            "Idempotency-Key"
    );

    @Override
    public HttpHeaders apply(HttpHeaders headers, ServerRequest request) {
        HttpHeaders forwarded = new HttpHeaders();
        for (String name : ALLOWED_HEADERS) {
            List<String> values = headers.get(name);
            if (values != null) {
                forwarded.put(name, List.copyOf(values));
            }
        }
        if (accessPolicy.publicRequests().matches(request.servletRequest())) {
            forwarded.remove(HttpHeaders.AUTHORIZATION);
        }
        forwarded.set(REQUEST_ID_HEADER, requireRequestId(request));
        forwarded.set("X-Forwarded-For", request.servletRequest().getRemoteAddr());
        return forwarded;
    }

    private static String requireRequestId(ServerRequest request) {
        Object value = request.servletRequest()
                .getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (!(value instanceof String requestId) || requestId.isBlank()) {
            throw new IllegalStateException(
                    "RequestIdFilter must provide request_id before proxying");
        }
        return requestId;
    }
}
```

- Начальный allowlist соответствует существующей политике Gateway; для новых маршрутов его расширяют
  по контракту, например для cookies или условных HTTP-запросов. Это не универсальный список для
  любого API.
- На публичных auth-маршрутах `Authorization` удаляется. На защищённых `Authorization` сохраняется
  без преобразования claims в `X-User-Id` или `X-Roles`. Проверку JWT и сессии на защищённом
  маршруте выполняет security до исходящего вызова; этот фильтр сам токен не валидирует.
- `X-Request-Id` берётся из атрибута с новым UUID, созданным Gateway; значение клиента не
  копируется. Присланные клиентом заголовки внутренней идентичности не пропускаются.
- Входящий `X-Forwarded-For` не копируется: пример использует адрес непосредственного клиента
  Gateway. Перед установкой балансировщика нужно отдельно определить доверенные прокси и обработку
  цепочки адресов.
- `Host`, длину и другие транспортные заголовки обслуживает транспорт. Проверить порядок стандартных
  и пользовательских фильтров: одинаковый `LOWEST_PRECEDENCE` не гарантирует, что наш фильтр
  окажется последним.
- При внедрении заменить существующую аналогичную политику в `RoutesConfiguration`, не оставляя два
  конкурирующих обработчика.

### `request/RequestIdFilter`

Код по образцу Identity; дополняет соглашение о request ID выше. Фильтр определяет ID до security,
сохраняет строку в текущем запросе, возвращает заголовок фронту и устанавливает MDC на время
синхронной обработки.

```java
package io.github.returnaction.fitness.gateway.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = "request_id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = RequestIdResolver.getOrCreate(request);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(REQUEST_ID_ATTRIBUTE, requestId)) {
            chain.doFilter(request, response);
        }
    }
}
```

MDC очищается при выходе из блока, включая ошибку; шаблон логов должен быть настроен на вывод
`request_id`. При переходе выполнения на другой поток MDC не переносится автоматически. Для
async/error dispatch поведение проверяется отдельно. Исходящий заголовок устанавливает
`ForwardedHeadersFilter`.

### `request/RequestIdResolver`

Gateway не разбирает клиентский заголовок. Resolver переиспользует внутренний атрибут текущего
запроса либо создаёт и сохраняет новый UUID. Это предотвращает смену ID в обработчиках ошибок и при
повторной обработке того же запроса.

```java
package io.github.returnaction.fitness.gateway.request;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class RequestIdResolver {
    private RequestIdResolver() {
    }

    public static String getOrCreate(HttpServletRequest request) {
        Object existing = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }
}
```

Атрибут создаётся внутри Gateway, а не из заголовка клиента. Принимающие сервисы сохраняют
полученный от Gateway ID; это правило генерации на внешней границе не переносится на них. Пример
требует проверки при реализации.

### `error/dto/ApiErrorResponse`: единый формат ошибок

По решению пользователя в Gateway копируется DTO из [Identity
ApiErrorResponse](../../services/identity-service/src/main/java/io/github/returnaction/fitness/identity/shared/web/ApiErrorResponse.java)
с тем же именем **`ApiErrorResponse`**. Меняется только Java-пакет; Gateway не получает зависимости
на код сервиса Identity. Это заменяет предварительное название `GatewayErrorResponse` в архитектуре.

```java
package io.github.returnaction.fitness.gateway.error.dto;

import java.util.List;

public record ApiErrorResponse(Error error) {

    public record Error(String code, String message, List<ErrorDetail> details, String requestId) {

        public Error {
            details = List.copyOf(details);
        }
    }

    public record ErrorDetail(String field, String code) {
    }
}
```

Для внешнего JSON сохраняется `SNAKE_CASE`, как в конфигурации Identity: Java-поле `requestId`
сериализуется как `request_id`. `details` передаётся списком, в том числе пустым, а не `null`.
HTTP-статус задаётся отдельно от DTO.

`GatewayExceptionHandler`, `GatewayErrorWriter` и `SecurityErrorHandler` используют этот единый
контракт для ошибок, сформированных Gateway. Выбор HTTP-статуса и кода ошибки выполняет
соответствующий обработчик. Внутренний текст исключения и stack trace клиенту не выдаются. Ответы
downstream обрабатываются согласно их контрактам, без создания ещё одного формата ошибок.

Это фиксация будущего копирования DTO в спецификации; исходный код сервисов этим изменением не
модифицируется.

### `error/GatewayErrorCode`: единые коды и сообщения

Коды и безопасные сообщения ошибок Gateway определяются только в этом enum.
Writer и MVC advice используют его, сохраняя единственный DTO ApiErrorResponse.
HTTP-статус и протокольные заголовки выбирает соответствующий обработчик.
Ошибки downstream передаются по его контракту, не преобразуются в этот enum.

```java
package io.github.returnaction.fitness.gateway.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GatewayErrorCode {
    NOT_FOUND("not_found", "Resource not found."),
    INVALID_REQUEST("invalid_request", "Check the request format."),
    INVALID_ACCESS_TOKEN("invalid_access_token", "Authentication required."),
    ACCESS_DENIED("access_denied", "Access denied."),
    METHOD_NOT_ALLOWED("method_not_allowed", "HTTP method is not supported."),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", "Content type is not supported."),
    IDENTITY_UNAVAILABLE("identity_unavailable", "Identity service is unavailable."),
    UPSTREAM_TIMEOUT("upstream_timeout", "Service response timed out."),
    UPSTREAM_UNAVAILABLE("upstream_unavailable", "Service is unavailable."),
    INTERNAL_ERROR("internal_error", "Unexpected server error.");

    private final String code;
    private final String message;
}
```

### `error/GatewayErrorWriter`

По решению пользователя используется **`ObjectMapper`**, получаемый от Spring через конструктор. Для
Jackson 3 используется пакет `tools.jackson.databind`. Writer не создаёт собственный mapper через
builder или `new`: он использует общие настройки сериализации приложения. `ObjectProvider` здесь не
нужен. Этот класс заменит текущую роль `web/ApiErrors`; исходники пока не изменяются.

```java
package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.error.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public final class GatewayErrorWriter {
    private final ObjectMapper mapper;

    public void write(HttpServletResponse response, int status,
            GatewayErrorCode error, String requestId) throws IOException {
        if (response.isCommitted()) {
            throw new IllegalStateException(
                    "Cannot write an API error after response is committed");
        }
        var body = new ApiErrorResponse(
                new ApiErrorResponse.Error(error.getCode(), error.getMessage(), List.of(), requestId));
        byte[] json = mapper.writeValueAsBytes(body);

        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(json.length);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Request-Id", requestId);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.getOutputStream().write(json);
    }

}
```

Для сериализации `requestId` как `request_id` при реализации настроить mapper Gateway так же, как в
Identity, и проверить фактический JSON:

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

Writer записывает `ApiErrorResponse`, HTTP-статус, JSON content type, `Cache-Control: no-store`,
`X-Request-Id` и для `401` — `WWW-Authenticate: Bearer`. Выбор статуса и кода ошибки остаётся в
обработчике исключений/безопасности. `details` для показанных технических ошибок — пустой список.

`resetBuffer()` очищает тело, но сохраняет заголовки. Пример предназначен для формирования ошибки до
отправки ответа. Если upstream-заголовки уже установлены, обработчик должен исключить несовместимые
с новым JSON заголовки, например `Content-Encoding`, прежде чем заменять ответ. После commit
заменить ответ нельзя: позднюю ошибку обрабатывает транспорт, без попытки дописать JSON к частично
отправленному телу и без повторного вызова writer.

Проверить внедрение настроенного mapper, формат `error.request_id`, HTTP-статус, заголовки и
поведение после commit. Код в spec не проверен сборкой.

### `error/GatewayExceptionHandler`

Используется `@RestControllerAdvice` с методами `@ExceptionHandler`, где исключения доступны
механизму Spring MVC. Ранее предложенный обычный `@Component` с ручным вызовом `handle()` не
является целевым решением. Обработчики возвращают единый `ApiErrorResponse`; не добавлять отдельный
формат или универсальный слой абстракций без конкретной необходимости.

По решению пользователя придерживаемся стиля существующего [ApiExceptionHandler
Identity](../../services/identity-service/src/main/java/io/github/returnaction/fitness/identity/shared/web/ApiExceptionHandler.java):

- `@Slf4j` Lombok создаёт поле `log`; ручной `LoggerFactory` не используется.
- Обычный класс с `@RestControllerAdvice`, без наследования от `ResponseEntityExceptionHandler` и
  без переопределения `handleExceptionInternal`.
- Для известных ошибок — явные методы `@ExceptionHandler` с результатом
  `ResponseEntity<ApiErrorResponse>`, а не `ResponseEntity<Object>`.
- Общий приватный `errorBody()` формирует DTO, Spring сериализует его настроенным `ObjectMapper`.
  Ручная запись JSON в MVC advice не требуется.
- Сохраняются необходимые протокольные заголовки, например `Allow` для `405`. Логи содержат request
  ID и тип ошибки, без токенов и персональных данных.

Обсуждённый фрагмент класса с обработчиками `405` и `415`:

```java
package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.error.dto.ApiErrorResponse;
import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .body(errorBody(
                        GatewayErrorCode.METHOD_NOT_ALLOWED,
                        request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(exception.getHeaders())
                .body(errorBody(
                        GatewayErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        request));
    }

    private ApiErrorResponse errorBody(GatewayErrorCode error,
            HttpServletRequest request) {
        return new ApiErrorResponse(new ApiErrorResponse.Error(
                error.getCode(), error.getMessage(), List.of(), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return RequestIdResolver.getOrCreate(request);
    }
}
```

Это фрагмент, а не полный перечень обработчиков. Остальные согласованные ошибки оформляются такими
же явными методами; для неожиданных ошибок используется `log.error("Unhandled request failure,
request_id={}, error_type={}", requestId(request), exception.getClass().getName())` через Lombok.
Существующий `RequestIdFilter` должен обеспечивать атрибут ID до MVC; генерация в helper — резервное
поведение по образцу Identity. Код в spec пока не проверен сборкой и интеграционными тестами.

| Где возникла ошибка | Обработка |
| --- | --- |
| Неизвестный путь или неправильный метод | RouteValidationFilter через GatewayErrorWriter, 404/405 с Allow для 405. |
| В MVC-маршруте или его функциональном фильтре, до отправки ответа | `GatewayExceptionHandler` через стандартный механизм MVC. |
| При аутентификации JWT или проверке прав Spring Security | `SecurityErrorHandler`: `AuthenticationEntryPoint` и `AccessDeniedHandler`. |
| Недействительная сессия в `SessionVerificationFilter` | Отказ через обработчик безопасности, `401`. |
| Техническая недоступность проверки сессии до MVC | Фильтр вызывает `GatewayErrorWriter`, `503`, без пропуска запроса. |
| Ошибка после начала передачи ответа | Транспорт завершает/прерывает передачу; новый JSON не дописывается. |

Advice не перехватывает автоматически исключения servlet-фильтров до `DispatcherServlet`. Для них
сохраняется локальная обработка известных отказов через writer. При реализации проверить advice на
фактических функциональных маршрутах выбранной версии Gateway MVC, а также поведение ошибок вне MVC.
Конкретные отображения транспортных исключений определить по используемому клиенту; не считать любую
`IOException` недоступностью downstream. Ошибки и статусы, возвращённые самим downstream,
сохраняются согласно его контракту.

Если Identity недоступен для обязательной проверки сессии, защищённый запрос не отправляется даже в
работающий downstream. Упрощение транспорта не меняет это правило и не отменяет отзыв сессий.

### `error/SecurityErrorHandler`

Стандартные точки расширения Spring Security: `AuthenticationEntryPoint` для отказа в аутентификации
(`401`) и `AccessDeniedHandler` для запрета доступа (`403`). Один класс реализует оба интерфейса:
его единая ответственность — преобразовать отказы безопасности в общий формат `ApiErrorResponse`.
Сериализация остаётся в `GatewayErrorWriter`, зависимость передаётся через конструктор, который
генерирует Lombok `@RequiredArgsConstructor`. Разделять класс на два без различающейся логики не
требуется.

```java
package io.github.returnaction.fitness.gateway.error;

import io.github.returnaction.fitness.gateway.request.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class SecurityErrorHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final GatewayErrorWriter errorWriter;

    @Override
    public void commence(HttpServletRequest request,
            HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                GatewayErrorCode.INVALID_ACCESS_TOKEN, requestId(request));
    }

    @Override
    public void handle(HttpServletRequest request,
            HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        errorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                GatewayErrorCode.ACCESS_DENIED, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return RequestIdResolver.getOrCreate(request);
    }
}
```

`commence()` вызывается при отказе аутентификации, например при просроченном JWT;
`SessionVerificationFilter` также использует его для недействительной сессии. Техническая
недоступность Identity остаётся отдельным `503`, а не `401`. `handle()` формирует `403`, когда
аутентифицированному пользователю запрещён доступ. Текст исключений клиенту не передаётся. Для
`access_denied` writer возвращает безопасное сообщение `Access denied.`.

Следующий фрагмент показывает только подключение ошибок; он не заменяет полную
цепочку и bearerTokenResolver из SecurityConfiguration.

Недостаточно одного `@Component`: обработчик явно подключается в уже описанном
`SecurityConfiguration` (параметр `errors`):

```java
.oauth2ResourceServer(resourceServer -> resourceServer
        .jwt(jwt -> jwt.decoder(jwtDecoder))
        .authenticationEntryPoint(errors)
        .accessDeniedHandler(errors)
)
.exceptionHandling(exceptions -> exceptions
        .authenticationEntryPoint(errors)
        .accessDeniedHandler(errors)
)
```

`@RestControllerAdvice` не заменяет эти обработчики ошибок security-фильтров. Все они используют
единый DTO, общий настроенный Jackson и GatewayErrorCode. Request ID берётся
из внутреннего атрибута запроса; независимая генерация в обработчиках запрещена.

Получение request ID в обработчиках делегируется общему `RequestIdResolver`, который использует
существующий атрибут, а при резервной генерации сохранить ID в запросе для последующего повторного
использования. Заголовок ответа, DTO и контекст логирования должны сохранять один ID; независимая
генерация в каждом обработчике недопустима. Основной путь остаётся прежним: `RequestIdFilter`
устанавливает ID до security. Показанные локальные helpers делегируют общему getOrCreate(request).

До завершения реализации проверить подключение в security chain, `401` для
отсутствующего/невалидного JWT и недействительной сессии, `403` для запрета, `503` для недоступности
проверки сессии, единый формат и совпадение request ID, а также отсутствие повторной записи после
commit. Код пока не проверен сборкой.

## Данные и конфиденциальность

Владельцы данных и схемы БД не меняются. Исходный bearer-токен и содержимое claims не записываются в
логи. Передача токена требует защищённого канала.

## Критерии готовности

- [ ] Первоначальная загрузка повторяется с ограниченным временем попытки и паузой
  до успеха; до ключей readiness=false, пользовательский API не обслуживается.
  Management доступен, liveness не падает из-за ожидания; shutdown отменяет повторы.
- [ ] После загрузки нет TTL/обновлений/сетевых обращений decoder; неизвестный kid
  даёт 401. Сбой Identity после инициализации не снимает readiness Gateway.
- [ ] Query string даёт 400 после проверки пути/метода, до JWT/CheckSession/downstream.
  Мобильные запросы поддерживаются без разрешающих CORS-заголовков и preflight.
- [ ] Прикладной лимит размера тела и собственный 413 по такому лимиту не добавлены.

- [ ] Неизвестный путь возвращает 404 not_found; неправильный метод —
  405 method_not_allowed с Allow из каталога. Проверены JSON/request ID
  и отсутствие JWT-проверки, CheckSession и downstream при этих отказах.

- [ ] Маршрутизация и policy строятся из единого каталога; дубли операций,
  пустой каталог и отсутствие адреса останавливают запуск. Проверены методы,
  пути и PUBLIC/AUTHENTICATED без независимых списков URI в потребителях.

- [ ] Проверен общий deadline 3 секунды: медленная проверка сессии уменьшает время downstream;
      зависание, ожидание соединения и медленная передача не продлевают бюджет. Проверены отмена
      ожидания, 503/504, отсутствие нового вызова после deadline и отсутствие повторной записи после
      начала ответа.

- [ ] Gateway всегда генерирует свой UUID, игнорируя клиентский X-Request-Id; один ID сохраняется в
      HTTP/gRPC, логах, заголовке ответа и JSON ошибки.

- [ ] На публичных auth-маршрутах JWT и CheckSession не проверяются; Authorization отсутствует в
      исходящем запросе при любом предъявленном токене. Проверены отсутствующий, валидный,
      просроченный и некорректный Bearer, refresh с действующим/недействительным refresh token, а
      также сохранение обязательной проверки JWT и сессии для logout-all.

- [ ] Gateway отвергает недействительный JWT до передачи запроса.
- [ ] Принимающий сервис получает исходный проверенный токен без user/role headers.
- [ ] Сохранены действующие ограничения доступа и отказ Gateway при некорректном токене.
- [ ] Сохранены проверки JWT, сессий и действующие правила auth API.
- [ ] Используется общий HTTP-транспорт с таймаутами, без отдельных клиентских registry, пулов и
      прикладных квот по downstream, без Resilience4j/semaphore.
- [ ] Проверены единый DTO ошибок, advice на MVC-маршрутах и отдельные обработчики отказов Spring
      Security и проверки сессии.
- [ ] Сохранены существующие auth-маршруты Identity; новые маршруты не подключены.
- [ ] Обновлены связанные контракты и выполнены проверки Gateway.

Создание SecurityContext в принимающих сервисах без повторной валидации JWT, method security,
изоляция контекста и проверка защищённого пути Gateway → сервис проверяются в отдельной работе при
подключении соответствующего API. Они не являются критериями завершения Spec 012.

## Открытые вопросы

Решения и открытые вопросы grilling зафиксированы в [questions.md](questions.md).
Согласованные решения приведены выше, оставшаяся техническая подготовка и
реализация отражены в [tasks.md](tasks.md). Спецификация готова к реализации;
реализация требует отдельной команды пользователя.
