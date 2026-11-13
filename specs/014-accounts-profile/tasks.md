# Задачи Spec 014: свой профиль Accounts

> **Спецификация:** [spec.md](spec.md)  
> **План:** [plan.md](plan.md)  
> **Статус:** in-progress — установлен по просьбе пользователя 2026-11-13; T001–T006 завершены и проверены 2026-11-13; T007–T010 ожидают отдельных команд. Коммиты ассистентом не выполняются.
> **Ветка:** 40-accounts-profile. Коммиты, push, merge, deploy и миграции пользовательской БД не разрешены.

T001–T010 выполняются только после отдельной команды пользователя. После каждого разрешённого пункта — отчёт с проверками и остановка для ревью; не начинать следующий пункт автоматически. Статус PR не выдумывать. Пропущенные проверки не закрывают задачу.

## Подготовка

- [x] T000 — Перенести согласованный черновик в Spec 014: spec, plan, tasks, questions; обозначить границы и ограничения. Документация подготовлена 2026-11-13, локальные ссылки проверены; полный перенос согласованного черновика в plan.md подтверждён сравнением; git diff --check прошёл. Реализация не входит в эту отметку.
- [x] T001 — Описать OpenAPI Accounts и подключить к Gateway. Разрешена и выполнена 2026-11-13 без коммита. Критерии: точные GET/PUT, восемь полей, required/null, enum, строгие типы, ограничения, примеры, Bearer, версия, ошибки и заголовки; общие схемы переиспользованы, refs/валидация проходят. Обновить конфликтующие проектные описания собственного профиля (PUT, дата, поля ответа, 400/422), не менять чужой профиль.

## Gateway

- [x] T002 — Добавить ACCOUNT, HTTP origin и два AUTHENTICATED-маршрута. Зависит от T001. Обновить примеры окружения, инструкции и тестовые настройки. Проверить запуск, JWT/CheckSession, отказ без forwarding, точные методы/пути, Bearer/body/request_id, статусы/no-store и транспортные ошибки. Существующие auth-маршруты и gRPC mTLS сохраняются.

## Accounts

- [x] T003 — Сверить существующую схему, код и тесты; подготовить единые HTTP-ошибки и DTO. Зависит от T002. Проверить required против null, неизвестные поля, camelCase, все восемь полей ответа, enum без ordinal/unknown-to-null, строгие числовые/строковые типы, JSON/content-type/query ошибки. Единый формат есть у security и MVC, без отдельного формата и утечки данных. Не запускать пользовательские миграции.
- [x] T004 — Добавить pre-auth фильтр и SecurityContext. Зависит от T003. UUID/известные роли, отсутствующие роли без USER, malformed claims, точные GET/PUT, однократная регистрация, authenticated(), management без пользовательского Bearer, deny остальных путей. Проверить очистку на успехе/ошибке и изоляцию параллельных запросов. Не объявлять локальное доверие проверкой HTTP caller.
- [x] T005 — Реализовать GET своего профиля. Зависит от T004. UUID только из контекста; чтение по user_id; полный ProfileResponse с явным null и no-store; отсутствие профиля → 503 без создания. Проверить недоступность БД и невозможность выбрать другого владельца.
- [x] T006 — Реализовать нормализацию и ProfileUpdateValidator. Зависит от T005. Strip до ограничений, пустые необязательные строки → null, все согласованные поля/Unicode/даты UTC/таймзоны, U+0000, агрегированные field/code без обращений к БД. Длина совпадает с VARCHAR, проверки границ покрыты тестами.
- [ ] T007 — Реализовать PUT с атомарным UPDATE RETURNING. Зависит от T006. READ COMMITTED, короткая реальная транзакция без сетевых вызовов, UUID+version, все поля, version+1 даже без изменений, updated_at, возврат после commit. Ноль строк → различить отсутствие/версию; только нужный unique-index → 409 после rollback. Проверить таймауты SQL/блокировок и отсутствие запросов в провалившейся транзакции.

## Проверка и завершение

- [ ] T008 — PostgreSQL-интеграционные проверки. Зависит от T007. Чтение после записи, очистка, свой ник/регистр/освобождение старого, два владельца с одинаковым ником, два запроса с одной версией, rollback всех полей и version, отсутствие профиля, сбои и ожидание блокировок. Повтор после потерянного ответа не перезаписывает данные. Проверки действительно выполнены, а не skipped; существующее provision не сломано.
- [ ] T009 — Сквозные серверные проверки через Gateway. Зависит от T008. Вход → GET → PUT → GET; ошибки security/DTO/DB проходят в согласованном формате, заголовки и identity auth без регрессии. Использовать разрешённую тестовую среду; запись в пользовательскую БД/миграции не выполнять без разрешения. Мобильный UI не требуется.
- [ ] T010 — Итоговая сверка и отчёт. Зависит от T009. Выполнить применимые команды Maven Wrapper/Spotless/SpotBugs и контрактные проверки; записать команды, результаты, failures/skips и ограничения. Актуализировать README/контракты/связанные документы, проверить ссылки, закрыть критерии spec только по доказательствам. Никакого автоматического коммита/публикации/перехода к другому этапу.

## Связанные Pull Request

PR не создан. Подготовка спецификации выполняется в 40-accounts-profile; разрешение на коммит и push отсутствует.

## Проверки T001 — 2026-11-13

- Добавлен `contracts/openapi/accounts-profile-v1.yaml`, ссылка из Gateway и
  описание собственного профиля; чужой профиль не входит в новый OpenAPI.
- `openapi-spec-validator==0.7.2`: `validate_url(Path(...).resolve().as_uri())`
  для Accounts, Gateway и Identity — PASS. Версии инструментов закреплены в
  `contracts/openapi/requirements-validation.txt`.
- Рекурсивное разрешение локальных `$ref` всех трёх контрактов — PASS.
- JSON Schema Draft 2020-12 с FormatChecker: 24 вхождения примеров запросов,
  ответов и ошибок — PASS; 20 отрицательных вариантов (пропуски восьми полей,
  неизвестное поле, null обязательных значений, неверные типы, enum и дата) — PASS.
- Локальные файловые цели Markdown-ссылок изменённых документов — PASS;
  `git diff --check` — PASS. Проверка схемы не заменяет runtime-проверки
  Jackson, нормализации, Unicode, IANA и относительной даты в T003–T009.
- Java-код, миграции и пользовательская БД не изменялись. Maven не запускался:
  задача касается контрактов и документации. Коммит и публикация не выполнялись.
- Работа остановлена после T001 для ревью; T002 не начата.

## Частичная проверка T002 — 2026-11-13

Пользователь реализует основной код самостоятельно; ассистенту разрешены ревью
и исправления тестов. T002 остаётся открытой.

- Проверены добавленные пользователем GET/PUT и настройки upstream.
  По решению пользователя от 2026-11-13 используются ACCOUNT / ACCOUNT_HTTP_BASE_URL.
  Переименование кода и настроек не требуется; план приведён к этим именам.
  Содержимое личного .env не проверялось.
- Исправлены RouteValidationTest, JwtValidationTest и TestUpstream: GET/PUT,
  Allow, семь маршрутов, обязательный upstream, два отдельных HTTP-сервера,
  пересылка метода/пути/Bearer/тела/request-id, статусов/no-store и отказ без forwarding.
- Пользователь подтвердил: Gateway ВСЕГДА создаёт свой request-id, игнорируя
  клиентский, даже корректный UUID. Прежнее описание сохранения клиентского ID
  исправлено в контракте, спецификации, плане и инженерных стандартах.
  Тест замены клиентского ID включён и прошёл; отключённых проверок не осталось.
- Команда `./mvnw.cmd -pl services/gateway-service -am test
  -Dmaven.repo.local=C:\Users\Oberg\.m2\repository`: 46 тестов Gateway,
  failures=0, errors=0, skipped=0. Использованы тестовые серверы и окружение;
  это не сквозной запуск с пользовательскими Identity/Accounts.
- Spotless apply/check ограничены тремя изменёнными Java-файлами тестов — PASS;
  git diff --check — PASS. Полный verify/SpotBugs в этой проверке не запускались.
- Для завершения остаются инструкции запуска и проверка
  тестового Python-сценария, оставшиеся проверки T002 и финальный verify.
  Production-код пользователя не менялся ассистентом; коммит не выполнялся.

## Дополнение к проверке T002 — 2026-11-13

По отдельной команде пользователя ассистент выполнил следующий блок без коммита/amend:

- Spotless для Gateway; сохранены существовавшие пользовательские правки.
- В verify_gateway_e2e.py добавлен ACCOUNT_HTTP_BASE_URL с временным портом Accounts.
- README Gateway согласован с семью операциями, ACCOUNT_HTTP_BASE_URL,
  GET/PUT, JWT/CheckSession, Allow и правилом request-id. Указано, что обработчики
  собственного профиля Accounts ещё предстоит реализовать.
- `./mvnw.cmd -pl services/gateway-service -am verify
  -Dmaven.repo.local=C:\Users\Oberg\.m2\repository` — BUILD SUCCESS:
  46 тестов Gateway, failures=0, errors=0, skipped=0; Spotless и SpotBugs прошли.
- AST-разбор Python-сценария, файловые ссылки README и git diff --check — PASS.
- Сквозной Python-сценарий с четырьмя сервисами не запускался; его runtime-проверка
  после изменения окружения остаётся открытой. T002 пока не отмечена завершённой.
  К T003 не переходили; пользовательская БД и миграции не затрагивались.

## Завершение T002 — 2026-11-13

- Пользователь разрешил запуск сквозного теста и закрытие T002 при успехе, без коммита.
- Сборка четырёх сервисов: `./mvnw.cmd -pl services/identity-service,services/accounts-service,services/coins-service,services/gateway-service -am -DskipTests package -Dmaven.repo.local=C:\Users\Oberg\.m2\repository` — BUILD SUCCESS.
  Пропуск тестов относится только к подготовке jar; полный verify Gateway ранее прошёл.
- Первый запуск `python -B services/gateway-service/src/test/python/verify_gateway_e2e.py`
  завершился по таймауту ожидания health Identity: сценарий использовал API-порт
  вместо отдельного management-порта. В сценарии выделен временный identity_management,
  задан IDENTITY_MANAGEMENT_PORT и исправлены обе проверки готовности Identity.
- Повторный запуск той же команды — exit 0, все пять групп проверок OK:
  четыре реальных сервиса и временный PostgreSQL; JWKS/mTLS; публичные auth-операции;
  refresh/replay, logout/logout-all и отзыв сессий; отказ Identity с 503;
  загрузка изменённых JWKS после рестарта Gateway; request-id и отсутствие секретов в логах.
- Временные контейнеры и Java-процессы теста после завершения отсутствуют.
  Личные .env и пользовательские базы не использовались; миграции выполнялись
  только в одноразовых тестовых базах. git diff --check и AST-проверка сценария — PASS.
- Вместе с ранее выполненными 46 тестами Gateway и полным verify критерии T002 закрыты.
  Пересылка GET/PUT профиля проверена отдельными тестовыми HTTP-серверами;
  реальный GET/PUT профиля Accounts остаётся объёмом T003–T009.
  К T003 не переходили. Коммит/amend/push не выполнялись.

## Дополнительный согласованный объём: camelCase JSON — 2026-11-13

Пользователь явно поручил перевести JSON API всех сервисов и мобильное приложение
на camelCase. Заголовки и БД сохраняются. Это отдельная согласованная работа;
она не означает завершение T003 или разрешение следующих задач профиля.

- [x] C001 — Перевести серверную сериализацию, DTO, OpenAPI, мобильные запросы/ответы,
  Postman, тестовые сценарии и JSON-примеры; проверить сервер, мобильный клиент
  и сквозной auth-сценарий. Сохранить чтение ранее записанной локальной сессии.


### Проверка C001 — 2026-11-13

- Полный `./mvnw.cmd verify` — PASS во всех 11 модулях: 231 тест,
  из них 228 выполнены успешно и 3 local Step CA smoke-теста штатно пропущены
  (требуют `fitness.localStepCaSmoke=true`). Форматирование и статический анализ прошли.
- Первый полный запуск упал на `HttpDeadlineTest.cancelsWhenClientStopsReadingResponse`:
  проверка пустой очереди таймера нестабильна. Повторный полный verify прошёл;
  тест не отключался, его логика не менялась. Эта нестабильность не считается устранённой.
- `flutter analyze` — PASS; `flutter test` — все 50 тестов PASS. Проверено чтение
  старой локальной сессии и запись camelCase; HTTP-клиент использует новые имена.
- Все три OpenAPI-контракта валидны; 29 примеров проверены по разрешённым схемам,
  имена properties не содержат snake_case. Postman JSON и синтаксис Python — PASS.
- `python -B services/gateway-service/src/test/python/verify_gateway_e2e.py` — exit 0,
  все пять групп PASS на Gateway, Identity, Accounts и Coins с одноразовыми БД:
  auth, refresh/replay, отзыв сессий, отказ Identity, JWKS/mTLS и requestId.
- Локальные ссылки изменённых документов и `git diff --check` — PASS.
  Заголовки, SQL/БД, Protobuf, коды ошибок и технические ключи логов сохранены.
- Это несовместимое изменение имён HTTP JSON: сервер и мобильный клиент нужно
  обновить вместе. Прогон приложения на устройстве не выполнялся.
  Реальные GET/PUT профиля ещё не реализованы; T003 и следующие задачи остаются открытыми.
  Коммит, push и миграции пользовательской БД не выполнялись.


## Частичное исправление T003: MVC-обработчик — 2026-11-13

По просьбе пользователя исправлен начатый ApiExceptionHandler Accounts:
ошибки разбора/типа — 400, MethodArgumentNotValidException — 422 с безопасными
field/code, неподдерживаемый Content-Type — 415, неожиданный сбой — безопасный
500 без текста исключения. Сохранены стандартные 405/Allow и 406.
requestId берётся из атрибута RequestIdFilter, повторной генерации нет.
Убраны неиспользуемые импорты фильтра и перегрузка response.

Проверка: Maven Wrapper `-pl services/accounts-service -am spotless:apply verify`
с `-Dtest=ApiExceptionHandlerTest,RequestIdFilterSecurityTest,RequestIdResolverTest,ProfileJsonNamingTest`,
`-Dsurefire.failIfNoSpecifiedTests=false` и ограничением Spotless на файлы обработчика,
его теста и RequestIdFilter — PASS: 11 тестов, 0 ошибок и пропусков; SpotBugs — PASS.
Новые 6 MVC-тестов проверяют 400/415/422/500, безопасные тела, соответствие requestId
заголовку, 405 и 406. Использован тестовый контроллер, без пользовательской БД.
Первый запуск остановился на wildcard import нового теста; импорт исправлен.
Два запуска не дошли до тестов из-за шаблона Spotless/экранирования Windows;
последующий запуск с корректными шаблонами успешен. git diff --check — PASS.

T003 остаётся открытой: общий формат Security, строгие настройки JSON и полная
матрица DTO/HTTP (в том числе query), подключение ошибок будущего валидатора
профиля ещё не завершены. ProfileUpdateValidator относится к T006; ранний @Valid
для правил профиля не добавлялся. Коммит не выполнялся.


## Частичная проверка T003: ошибки Security через resolver — 2026-11-13

По явному поручению пользователя выбран подход Identity: SecurityExceptionHandler
реализует AuthenticationEntryPoint и AccessDeniedHandler и передаёт исключения
в HandlerExceptionResolver. ApiExceptionHandler формирует 401 invalid_access_token
и 403 access_denied с details=[], requestId и no-store; 401 содержит
WWW-Authenticate: Bearer. SecurityErrorWriter не добавлялся.
При необработанном исключении resolver — пустой 500; отправленный ответ не переписывается.
Существующие management-разрешения и denyAll сохранены, pre-auth фильтр T004 не добавлялся.

Проверка: `./mvnw.cmd -pl services/accounts-service -am spotless:apply verify`
с `-Dtest=ApiExceptionHandlerTest,SecurityExceptionHandlerTest,RequestIdFilterSecurityTest,RequestIdResolverTest,ProfileJsonNamingTest`
и `-Dsurefire.failIfNoSpecifiedTests=false` — PASS: 15 тестов, 0 failures/errors/skips.
Spotless ограничен изменёнными файлами Security и ApiExceptionHandler; SpotBugs — PASS.
Проверены реальные отказы Spring Security через MockMvc: анонимный 401,
аутентифицированный 403, совпадение ID тела/заголовка, заголовки и доступность health.
Отдельно проверены fallback 500 и защита уже отправленного ответа. git diff --check — PASS.
Пользовательская БД и миграции не использовались, коммит не выполнялся.

T003 остаётся открытой: строгий Jackson, полная матрица DTO/HTTP и запрет query
ещё требуют реализации/проверки. В будущем фильтре T004 ошибки разбора Bearer
нужно передавать в этот же механизм; сам фильтр пока отсутствует.


### Настройка форматирования по просьбе пользователя — 2026-11-13

В config/eclipse-java-formatter.xml включено объединение перенесённых строк;
для параметров методов и конструкторов выбран compact wrap с отступом в один
уровень. Лимит 120 сохранён. Конструктор SecurityExceptionHandler помещается
ровно в 120 символов и теперь форматируется в одну строку.
`./mvnw.cmd spotless:apply spotless:check` — PASS во всех 11 модулях;
`git diff --check` — PASS. Изменение стиля не закрывает оставшиеся критерии T003.


Уточнение форматирования record — 2026-11-13: отдельная настройка
alignment_for_record_components=83 (принудительный перенос следующих компонентов
с выравниванием по первому); parentheses_positions_in_record_declaration=common_lines.
ProfileResponse сохраняет первое поле рядом с именем record, остальные — по одному
на строке. Параметры обычных методов/конструкторов остаются compact.
Обнаружен устаревший кэш Spotless: после удаления только target/spotless-index
повторно выполнен `spotless:apply spotless:check` во всех 11 модулях — PASS.
Оба согласованных правила стиля применены ко всему Java-коду, поэтому diff
форматирования затрагивает множество файлов. git diff --check — PASS.
Функциональные тесты для изменения только форматирования повторно не запускались.


### Jackson: настройки в YAML — 2026-11-13

По просьбе пользователя обычные переключатели добавлены в application.yaml Accounts:
spring.jackson.deserialization, mapper и datatype.enum. Java-конфигурации Jackson
на момент изменения не было; специальные withCoercionConfig не добавлялись.
Первый запуск выявил неверное размещение enum-переключателей в deserialization;
для Jackson 3 исправлено на datatype.enum, повторный запуск успешен.
Maven verify для Accounts с зависимостями и выбранными тестами
RequestIdFilterSecurityTest, ProfileJsonNamingTest, ApiExceptionHandlerTest,
SecurityExceptionHandlerTest, RequestIdResolverTest — PASS, 16 тестов без ошибок/пропусков.
На Spring JsonMapper проверены запрет лишних полей, дробной/строковой version,
числового/неизвестного/неверного регистра enum и допустимость nullable firstName.
Spotless для изменённого теста и SpotBugs — PASS; git diff --check — PASS.
T003 остаётся открытой: специальные coercion-правила, остальные DTO/HTTP-проверки
и запрет query ещё не завершены. Пользовательская БД не затронута, коммита нет.


## Завершение T003 — 2026-11-13

Пользователь явно разрешил завершить оставшуюся работу. Добавлены специальные
coercion-правила Jackson; обычные переключатели сохранены в application.yaml.
Для входного dateOfBirth добавлен ProfileDateDeserializer: проверяет строковый
тип, точный YYYY-MM-DD и существование календарной даты; не проверяет бизнес-диапазон.
Это необходимо, поскольку стандартный Jackson 3 LocalDateDeserializer принимает
массивы и не применяет к ним настройки coercion. Разбор всего DTO остаётся у Jackson.
Enum ProfileVisibility и пользовательские бизнес-правила не изменялись.
ProfileRequestInterceptor отклоняет query string на точных GET/PUT /me после Security;
другие маршруты не затрагиваются. Ошибки используют общий ApiExceptionHandler.

ProfileHttpContractTest использует Spring mapper, настоящие MVC-конвертеры,
настройки и обработчики; только контроллер и допуск к /me заменены тестовыми.
85 проверок охватывают пропуск каждого поля, неверные JSON-типы, лишние поля,
camelCase, enum, число/строку/дробь/overflow version, строгую дату, nullable-поля,
восемь полей ответа, Content-Type, пустое тело и query string. Разобранные null
обязательных значений и значения вне бизнес-диапазона оставляются для T006.
Существующие MVC-тесты проверяют 422 с field/code, безопасный 500, 405/406;
тесты Security — единый JSON 401/403, заголовки и общий requestId.

- Выбранные DTO/HTTP/security-тесты: 101 выполнен, 0 failures/errors/skips.
- `./mvnw.cmd -pl services/accounts-service -am verify` — PASS.
  Accounts: 121 тест, 120 выполнены успешно, 1 LocalStepCaSmokeTest штатно
  пропущен без fitness.localStepCaSmoke=true. gRPC contracts: 3 теста PASS.
  PostgreSQL/Testcontainers: миграция/ограничения и создание профиля — выполнены;
  интеграционный gRPC mTLS — выполнен. Spotless и SpotBugs — PASS.
- Схема, entity и provisioning сверены; новая миграция не нужна.
  Пользовательская БД не использовалась; миграции выполнялись только тестами
  в одноразовом PostgreSQL. README Accounts дополнен границами реализации.
- git diff --check — PASS. Коммит/push не выполнялись.

T003 закрыта. T004 не начиналась: production denyAll сохранён; реальные GET/PUT
и бизнес-валидация/сохранение появятся в T004–T007. Сквозной сценарий профиля
не заявляется выполненным до T009. После задачи работа остановлена для ревью.


### Подготовка T004: зависимость JWT — 2026-11-13

По отдельной просьбе пользователя в pom.xml Accounts добавлена только зависимость
com.nimbusds:nimbus-jose-jwt с существующей версией ${nimbus-jose-jwt.version}.
`./mvnw.cmd -pl services/accounts-service -am compile` — PASS;
git diff --check — PASS. Приложение, тесты и миграции не запускались.
Фильтр Bearer не добавлен, T004 остаётся открытой. Коммита нет.


## Проверка и завершение T004 — 2026-11-13

Пользователь написал ProfileAuthenticationFilter и поручил проверить/исправить,
сохранив имя securityExceptionHandler. Разбор sub/roles и очистка контекста
соответствуют требованиям; имя сохранено в фильтре и SecurityConfiguration.
Исправлено отсутствующее подключение: фильтр создаётся только внутри цепочки
Spring Security перед UsernamePasswordAuthenticationFilter, без servlet-bean.
Точные GET/PUT /api/v1/accounts/me требуют authenticated(); management-разрешения
и denyAll для остальных операций сохранены. Добавлены описание границы доверия
и проверки; лишнее throws ServletException у shouldNotFilter удалено.

- ProfileAuthenticationFilterTest: 22 проверки — UUID principal, известные роли,
  отсутствующие/пустые/неизвестные роли без автоматического USER, повреждённые
  claims и Bearer, точные методы/пути/contextPath, очистка при успехе/ошибке,
  параллельная изоляция контекстов — PASS.
- ProfileAuthenticationSecurityTest: 5 проверок production-цепочки с тестовым
  контроллером — GET/PUT без ролей допускаются без HTTP-сессии; единый 401,
  requestId/no-store/WWW-Authenticate, ошибка контроллера остаётся 500,
  последующий запрос без токена получает 401; остальные методы/пути закрыты,
  health/info/prometheus доступны без пользовательского Bearer; фильтр один
  в цепочке и не зарегистрирован отдельным bean — PASS.
- Выбранные проверки фильтра/Security: 31 тест PASS.
- `./mvnw.cmd -pl services/accounts-service -am verify` — PASS.
  Accounts: 148 тестов, 147 успешно, один LocalStepCaSmokeTest штатно пропущен
  без fitness.localStepCaSmoke=true. gRPC contracts: 3 теста PASS.
  PostgreSQL/Testcontainers и интеграционный gRPC mTLS выполнены.
  Spotless, SpotBugs, git diff --check — PASS.
- Реальные контроллеры/чтение/сохранение профиля не добавлялись. Подпись JWT
  и активность сессии остаются ответственностью Gateway; личность HTTP caller
  этим фильтром не подтверждается. README актуализирован.
- Пользовательская БД/миграции не использовались. Коммит/push не выполнялись.

T004 закрыта; T005 ожидает отдельной команды. Работа остановлена для ревью.


## Проверки T005 — 2026-11-13

- Завершён GET /api/v1/accounts/me: ProfileController получает UUID principal,
  ProfileService читает запись по user_id и возвращает все восемь полей, включая
  null; Cache-Control: no-store. Отсутствие профиля — 503 без создания записи.
- Временные ошибки доступа к БД, таймаут запроса и ошибка открытия транзакции
  переводятся в profile_unavailable с сохранением причины исключения.
  Ошибки SQL/программирования не маскируются под временную недоступность.
  Лог обработчика содержит request-id и безопасный тип причины без её текста.
- ProfileReadIntegrationTest: четыре проверки с реальным PostgreSQL Testcontainers,
  production Security/MVC/controller/service/repository. Полный ответ и null,
  неизменность строки/version/updated_at, два владельца, попытка подмены владельца
  через заголовок/тело/query, отказ без Bearer, отсутствующая запись без INSERT,
  503 после остановки только тестового PostgreSQL. Заголовки и requestId проверены.
- ProfileServiceTest: семь проверок поиска, отсутствия записей/повторов,
  классификации ошибок и сохранения cause. ProfileUnavailableLoggingTest:
  безопасный лог и общий ответ без SQL/секретов. Старые HTTP/security-тесты
  используют настоящий GET-контроллер с mock сервиса; тестовый PUT сохранён.
- `./mvnw.cmd -pl services/accounts-service -am spotless:apply verify
  -Dmaven.repo.local=C:\Users\Oberg\.m2\repository` — BUILD SUCCESS.
  Accounts: 160 тестов, 159 выполнены успешно, 0 failures/errors, 1 skip —
  отдельный opt-in LocalStepCaSmokeTest. gRPC contracts: 3/3 успешно.
  PostgreSQL-тесты и MutualTlsIntegrationTest выполнены, не skipped.
  Spotless check и SpotBugs — PASS, 0 замечаний. Итоговый лог: .t005-verify.log.
- README и статусы Spec/Plan актуализированы. Пользовательская БД и миграции
  не затрагивались; коммит не выполнен. Сквозной Gateway → Accounts остаётся
  T009; PUT, валидация и запись остаются T006–T008. Остановка после T005 для ревью.


## Частичная проверка T006 — 2026-11-13

- Пользователь добавил нормализатор, исключение валидации, обработчик 422 и
  проверки visibility/version/nickname/bio. Ассистент по отдельной просьбе
  подключил validateNickname и validateBio к validate и добавил тесты.
- ProfileUpdateValidatorTest: 12 проверок успешно, без skips: границы ника,
  недопустимые символы, null/пустой ник, 500/501 Unicode-кодовая точка в bio,
  U+0000 и сбор ошибок нескольких полей.
- Maven Wrapper: `-pl services/accounts-service -am spotless:apply verify
  -Dtest=ProfileUpdateValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`
  с локальным Maven-кэшем — BUILD SUCCESS; Spotless и SpotBugs — PASS.
  Лог: .t006-validator-check.log. Полный набор тестов на этом шаге не запускался.
- T006 остаётся открытой: ещё нужны проверки имён, даты рождения и timezone,
  остальные проверки нормализации/валидации и HTTP 422. PUT не подключён.
  Коммит не выполнялся.


## Завершение T006 — 2026-11-13

- По просьбе пользователя завершены нормализация и проверки всех восьми полей.
  Исправлено использование NICKNAME_PATTERN и создание валидатора с Clock в тестах.
  ClockConfiguration задаёт UTC; тесты используют фиксированный момент, включая
  несовпадение локального дня с UTC и високосную дату. Продуктовые правила сохранены.
- ProfileUpdateNormalizerTest: 7 проверок — strip, null/пустые значения, сохранение
  внутренних пробелов и остальных полей, неизменность исходного DTO, идемпотентность,
  нормализация до лимитов; U+0000 не скрывается нормализацией.
- ProfileUpdateValidatorTest: 56 проверок — границы длины, Unicode/комбинируемые
  знаки/апострофы, отсутствие букв, запрещённые символы, U+0000, даты UTC и границы
  150 лет, IANA/UTC против неизвестных зон и смещений, visibility/version,
  агрегация ошибок. Репозитории и другие источники данных валидатору не нужны.
- ProfileHttpContractTest: 92 проверки, включая 7 новых сценариев 422,
  нормализации до проверки длины и U+0000 в каждом текстовом поле. Настоящие
  нормализатор/валидатор/обработчик работают через тестовый PUT-адаптер; проверены
  field/code без значений, requestId, no-store и отсутствие вызовов сервисов.
  Разделение 400 при неверном JSON и 422 при неверных значениях сохранено.
- ProfileReadIntegrationTest: 5 проверок с PostgreSQL, включая новую проверку
  реальных VARCHAR(100/500): supplementary Unicode сохраняется на границе,
  101-я кодовая точка имени/фамилии и 501-я в bio отклоняются PostgreSQL.
- `./mvnw.cmd -pl services/accounts-service -am spotless:apply verify
  -Dmaven.repo.local=C:\Users\Oberg\.m2\repository` — BUILD SUCCESS.
  Accounts: 231 тест, 230 успешно, 0 failures/errors, один opt-in LocalStepCaSmokeTest
  пропущен. gRPC contracts: 3/3 успешно. PostgreSQL и gRPC mTLS выполнены.
  Spotless и SpotBugs — PASS, 0 замечаний; лог .t006-verify.log.
- README, spec и plan актуализированы. T006 закрыта; production PUT/запись остаются
  T007, сквозная проверка через Gateway — T009. Пользовательская БД не использовалась,
  коммит не выполнялся. Работа остановлена для ревью.
