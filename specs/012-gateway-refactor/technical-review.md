# Техническая проверка перед реализацией Spec 012

Дата: 2026-11-12. Область: T001/T002, документация и исходники библиотек.
Код приложения и тестового стенда в этой проверке не писался, приложение не запускалось.
Старый Gateway не использован как источник требований.

Начальные разделы описывают историческую проверку. Исполняемые результаты Э09
добавлены в конце документа и заменяют ограничения T002b/T002c ниже.

## Матрица текущих операций (T001)

Все пути ниже имеют префикс `/api/v1/auth/`, метод POST. Остальные пути — 404,
неверный метод — 405 с Allow, query после этих проверок — 400.

| Путь | Доступ | JWT / CheckSession | Authorization в Identity | Проверка |
| --- | --- | --- | --- | --- |
| register | PUBLIC | не вызываются | удаляется | T005b |
| login | PUBLIC | не вызываются | удаляется | T005b |
| refresh | PUBLIC | не вызываются | удаляется | T005b |
| logout | PUBLIC | не вызываются | удаляется | T005b |
| logout-all | AUTHENTICATED | JWT, затем сессия | исходный проверенный bearer | T005b/T005c |

До первоначальной загрузки ключей пользовательские операции не обслуживаются.
JWT: ES256, issuer/audience, обязательные sub/sid/jti/iat/exp, формат roles,
срок и границы времени — T005c. Неверный JWT/неизвестный kid/INVALID сессия — 401;
техническая ошибка CheckSession — 503. HTTP upstream: 502/504 по причине, без retry.
Новый request ID и allowlist исходящих заголовков — T005d и Э08.
DTO ошибок и обработка после commit — T005f. Профили и обязательные настройки — Э02.
CORS/query — T008/T012; без прикладного лимита тела — T011.

## Совместимость и зависимости (T002a)

Официально поддерживаются Java 25 и Spring Boot 4.1.2; Spring Cloud 2025.1.4
совместим с Boot 4.1.x (поддержка начиная с Cloud 2025.1.2).
Источники: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Spring Cloud compatibility](https://spring.io/projects/spring-cloud/).

Ранее полученное дерево зависимостей в verification/dependencies.txt содержит
Gateway MVC 5.0.4, Framework 7.0.10, Jackson 3.1.7, Security crypto 7.1.2,
HttpClient 5.6.5 / HttpCore 5.4.4. Это результат разрешения модели Maven,
не подтверждение компиляции всех зависимостей или совместимости всего приложения.
Для JWT нужны оба модуля spring-security-oauth2-resource-server и spring-security-oauth2-jose,
а не только crypto или Nimbus. Их загрузка и сборка ещё не подтверждены.
Источник: [Spring Security JWT dependencies](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
Версию gRPC 1.85.0 использует конфигурация проекта; полный runtime gRPC здесь не проверен.

Ранее Maven Wrapper завершился внутренней ошибкой до запуска Maven. Обход через
скачанный Wrapper-ом Maven 3.9.11 позволил получить дерево, но не делает стандартный
вход сборки проверенным. В Э01 с разрешением на код/сборку воспроизвести и устранить
проблему окружения Wrapper; не заменять стандартный способ сборки молча.

## Отмена HTTP (T002b)

Проверены локальные опубликованные sources.jar Gateway MVC 5.0.4 и Spring Web 7.0.10:

- RestClientProxyExchange.exchange вызывает exchange с close=false; тело ответа
  копируется позднее в callback GatewayServerResponse и закрывается в finally.
  Следовательно, отмену нельзя снимать при возвращении заголовков или ServerResponse.
- HttpComponentsClientHttpRequestFactory имеет protected createHttpUriRequest и
  postProcessHttpRequest. Apache HttpUriRequestBase имеет cancel/abort и зависимость
  отмены. Это доступная точка для адаптера общего транспорта, не отдельный пул на сервис.
- Выбранное направление: адаптер фабрики регистрирует отменяемый запрос и остаток
  deadline; обёртка ответа снимает регистрацию после закрытия тела, ошибки также
  освобождают регистрацию. Планировщик не пишет JSON.

[Apache API отмены](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/classic/methods/HttpUriRequestBase.html).

Существенное ограничение: RestClientProxyExchange читает входной ServletInputStream
через StreamUtils.copy. Отмена исходящего HTTP-запроса сама по себе не доказывает
прерывание заблокированного чтения от медленного клиента или записи ответа ему.
Строгий общий предел до конца передачи требует проверки контейнера и адаптера.
Это остаётся открытым в T002b/T005e; нельзя подменять его обычным read timeout
или объявлять гарантированным по наличию cancel(). После commit новый JSON запрещён.

## Ошибки (T002c)

В sources.jar Spring Web MVC 7.0.10 метод ExceptionHandlerExceptionResolver.shouldApplyTo
явно допускает HandlerFunction при наличии global advice. Глобальный
@RestControllerAdvice без ограничивающих selectors подходит функциональным маршрутам.
При selectors по классу/пакету контроллера применимость к HandlerFunction не гарантируется.
Ошибки servlet-фильтров до DispatcherServlet требуют локального writer;
security использует entry point/access denied handler, firewall — свой обработчик.
Advice не заменяет эти пути. Фактические статусы и единый DTO проверяются в T005f.

## Готовность (T002d)

Выбранный механизм: первоначальная загрузка в ApplicationRunner до завершения startup;
один атомарно опубликованный признак готовности ключей. До успеха отдельный gate
на пользовательском API не пропускает запрос к маршрутам/security/downstream,
включая прямой доступ к порту. Диагностический отказ 503 не является обслуживанием
бизнес-операции. Management обрабатывается отдельно и остаётся доступным.
Проверка gate выполняется после request ID, до проверки пользовательского маршрута.

Readiness отражает готовность ключей и локальное состояние приложения; liveness
не зависит от доступности Identity. Один readiness endpoint без gate не закрывает порт.
Повторы первоначальной загрузки имеют timeout 3s и паузу 5s, прекращаются при shutdown
или после первого успешного набора. После загрузки падение Identity не меняет этот набор
и само по себе не снимает readiness. На каждый защищённый запрос остаётся CheckSession.
Источник: [Spring Boot application availability](https://docs.spring.io/spring-boot/reference/features/spring-application.html).

## Вывод и границы доказательства

Совместимость основных поколений подтверждена, точки расширения найдены,
решение по готовности и разделение ошибок определены. Нового продуктового выбора нет.
T001 закрывается этой матрицей. T002a–d целиком не закрываются: исполняемая сборка,
поведение отмены/медленного тела, гонки, фактические ответы и прямой доступ до готовности
не проверены запуском. Эти доказательства получают с кодом на соответствующих этапах.

## Исполняемая проверка T002a2 (2026-11-12)

Этот результат заменяет прежние ограничения T002a выше: Wrapper исправлен в T002a1,
а verification теперь содержит исполняемые тесты. T002b–d остаются открытыми.

TargetStackTest запускает Boot на случайном loopback-порту с virtual threads,
реальной цепочкой Resource Server и функциональным маршрутом Gateway MVC.
Валидный ES256 JWT проходит в локальный HTTP upstream; JSON создаётся и
читается Spring ObjectMapper из Jackson 3. Отсутствующий/повреждённый токен,
неверная подпись, неизвестный kid, неправильные issuer/audience и истёкший exp
дают 401; счётчик upstream не растёт.

Проверен API NimbusJwtDecoder(JWTProcessor): ImmutableJWKSet содержит только
открытый EC-ключ, JWSVerificationKeySelector допускает ES256. У этого источника
нет сетевого клиента и refresh. Проверки времени переданы Spring с фиксированным
Clock и нулевым skew. Полный AccessTokenClaimsValidator здесь не реализуется.

GrpcRuntimeTest выполняет реальный unary-вызов через grpc-netty-shaded
между loopback-сервером и каналом, с deadline и проверкой завершения ресурсов.
Это тест совместимости runtime, не реализация CheckSession и не проверка TLS.

Итог: 3 теста, 0 failures/errors/skips; verify и Spotless успешны.
Историческое дерево хранилось в `verification/dependencies.txt`; этот временный
стенд удалён пользователем перед новой реализацией. Актуальные зависимости
определены POM основного модуля и подтверждены сборками Э09–Э11.
Стек: Boot 4.1.2, Cloud BOM 2025.1.4, Gateway MVC 5.0.4, Framework 7.0.10,
Security OAuth2 Resource Server/Jose 7.1.2, Jackson 3.1.7, gRPC 1.85.0.
Nimbus закреплён на 10.5, как в основном проекте; BOM по умолчанию выбирал 10.9.1,
поэтому итоговый прогон повторён с 10.5.
Инструменты: Temurin 25.0.5, Maven 3.9.11, Surefire 3.5.4,
Spotless 3.10.4 / Eclipse formatter 4.37; compiler 3.15.0 из Boot parent.

Команда из корня (параметр cache нужен для окружения агента):
```powershell
.\mvnw.cmd "-Dmaven.repo.local=C:\Users\Oberg\.m2\repository" -B -ntp -f specs/012-gateway-refactor/verification/pom.xml verify org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree "-DoutputFile=dependencies.txt"
```

API сверены с [Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
и [Gateway MVC](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/java-routes-api.html).
Исполняемые результаты относятся к закреплённым выше версиям.
Стенд не доказывает общий deadline, readiness, все claims, TLS, Token Relay
или полную реализацию пяти операций. Эти проверки остаются в своих задачах.

## Проверка основного Gateway, Э09 (2026-11-12)

HttpDeadlineScope создаётся после security с остатком RequestDeadline и охватывает
исходящий HTTP, чтение ответа и финальный flush клиенту. Один общий планировщик
отменяет Apache HttpUriRequestBase; закрытие области синхронизировано с отменой,
удаляет задачу из очереди и очищает собственный interrupt. Общий HTTP-пул закрывается
при остановке контекста. Проверено отсутствие нового HTTP после истечения бюджета
проверки сессии и отсутствие влияния прежней отмены на последующие запросы.

Одного Thread.interrupt недостаточно: Tomcat 11.0.27 игнорирует прерывание ожидания
в NioEndpoint. Поэтому DeadlineHttp11Protocol предоставляет текущему обработчику
его SocketWrapperBase, а DeadlineInputStream задаёт остаток срока перед каждым
чтением. DeadlineOutputStream ограничивает запись; таймер закрывает соединение,
если клиент не читает ответ. Привязка ограничена текущим HTTP/1.1-коннектором Tomcat;
поддержка других контейнеров и HTTP/2 не проверена и не заявляется.

При upload timeout Tomcat сам вызывает sendError(408). Внутренний ERROR dispatch
разрешён security и обрабатывается GatewayErrorController как общий 504 с тем же ID.
DISABLE_SWALLOW_INPUT прекращает попытки дочитать отменённую загрузку. Внешний
запрос к /error не открывает обход каталога: он по-прежнему получает 404.
Обычные MVC-ошибки обрабатывает глобальный GatewayExceptionHandler; firewall имеет
отдельный writer. После commit фильтр закрывает соединение без нового JSON.

HttpDeadlineTest/HttpsDeadlineTest проверяют: ожидание аренды соединения, зависший
TLS handshake, заголовки, медленное тело до/после commit, неподвижную и поэтапную
загрузку, клиента без чтения ответа, окончание около deadline, следующие запросы,
единый firewall 400 и очистку очереди таймеров. При отмене lease Apache лениво
удаляет заявку при освобождении соединения; тест подтверждает удаление и отсутствие
отложенного вызова. Проверены upstream disconnect → 502 без повторов и таймаут → 504.
GatewayErrorsTest проверяет advice на функциональных маршрутах: 405/Allow, 415, 500,
общий DTO и отсутствие приватной диагностики в JSON и логах.
JwtValidationTest подтверждает передачу 2 МиБ без прикладного лимита размера.

Maven Wrapper `-pl services/gateway-service -am spotless:apply verify` прошёл:
45 тестов Gateway, 3 теста контрактов, Spotless и SpotBugs без ошибок.
Использован cache `C:\Users\Oberg\.m2\repository`. Настоящий Identity и его БД
этим прогоном не запускались; сквозной этап Э10 остаётся открытым.

## Сквозная проверка Э10 (2026-11-12)

В `services/gateway-service/src/test/python/verify_gateway_e2e.py` добавлен сценарий
с настоящими Gateway, Identity, Accounts и Coins. Он использует существующие функции
старта/выпуска сертификатов из registration-e2e, но не его старые настройки Gateway.
Сервисы упакованы Maven Wrapper, PostgreSQL 17-alpine запускается одноразово с тремя
отдельными тестовыми БД. JWT/mTLS-ключи временные, профиль test, .env не импортируется.
Системные переменные для JVM передаются по явному списку, конфигурация тестов задаётся
отдельно. Пользовательские БД не изменяются. Gateway сохраняет deadline 3s.

Сценарий прошёл: пять операций; все публичные операции с четырьмя вариантами bearer;
действительный refresh, replay с отзывом сессии, logout/logout-all с реальным
изменением состояния Identity; 401 на отозванный JWT; 503 при остановке Identity;
readiness после первоначальной загрузки остаётся UP. При смене JWT-ключа Identity
новый kid отклоняется Gateway до его рестарта. После рестарта новый принимается,
удалённый старый отклоняется. HTTP/JSON ID совпадают, пароли, email, idempotency key,
access/refresh-токены отсутствуют в логах всех четырёх сервисов.

Повторный основной `verify`: 45 тестов Gateway + 3 теста контрактов, Spotless и
SpotBugs без ошибок. Расширена проверка регистрации фильтров: servlet-фильтры
подключены один раз, SessionVerificationFilter и RouteValidationFilter находятся
только в security chain и тоже ровно по одному экземпляру.
Команды сборки jar и сквозного запуска добавлены в README Gateway.

## Финальная проверка Э11 (2026-11-12)

`clean verify` через Maven Wrapper для `services/gateway-service` с `-am` прошёл:
45 тестов Gateway + 3 теста контрактов, без пропусков, Spotless и SpotBugs без ошибок.
Сборка с clean исключает оставшиеся классы прежней реализации из target.
Удаление старых исходников выполнено пользователем в fb56c0c; модуль сохранён.

Docker-образ `fitness-gateway:012-verify` успешно собран штатным Dockerfile из корня.
Добавлено копирование `config`, необходимого для настроек форматтера сборки.
Образ не развёртывался. .env.example согласован с адресами и сертификатами Identity;
существующий локальный .env не перезаписывался. README содержит команды запуска,
readiness и тестов. Статусы спецификации и плана приведены к текущему результату.

Границы результата: адаптер отмены клиентского ввода/вывода проверен для Tomcat
HTTP/1.1; HTTP/2 и другой контейнер требуют отдельной работы. Защита от обхода Gateway,
pre-authentication принимающих сервисов и выпуск не входят в выполненный объём.
