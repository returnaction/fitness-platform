# Accounts Service

## Локальное ограничение HTTP — Spec 013 T002

Локальный профиль привязывает HTTP к 127.0.0.1. Это ограничивает доступ к API и
management того же listener с других компьютеров, не меняя существующий mTLS gRPC.
Настройка контейнерной сети и публикации портов выполняется отдельно при развёртывании.


## Подготовка HTTP профиля — Spec 014 T003

Контракт: [Accounts OpenAPI](../../contracts/openapi/accounts-profile-v1.yaml).
Общие JSON-настройки находятся в `src/main/resources/application.yaml`, запреты
преобразования числа/boolean в строку и пустых строк в число/enum — в
`JacksonConfiguration`. Все восемь полей PUT должны присутствовать; явный null
сохраняется для проверки правил значений ProfileUpdateValidator (T006).
Значения строк на этапе разбора не нормализуются.

`ProfileDateDeserializer` принимает только строку YYYY-MM-DD с существующей
календарной датой (либо null через Jackson). Стандартный LocalDateDeserializer
Jackson 3 допускает также массивы и другие представления, поэтому для входного
поля dateOfBirth используется отдельный десериализатор. Диапазон дат проверяется
позже бизнес-валидатором, а не десериализатором.

`ProfileRequestInterceptor` запрещает query string на точных GET/PUT
`/api/v1/accounts/me` и устанавливает no-store. Он выполняется в MVC после
Security. Ошибки MVC и отказы Security используют ApiExceptionHandler;
SecurityExceptionHandler передаёт исключения в HandlerExceptionResolver.

GET и PUT реализованы в ProfileController. ProfileHttpContractTest использует
настоящие контроллер и ProfileService, mock репозитория/транзакционного updater
и тестовую цепочку Security, разрешённую только в его контексте.
Проверки преобразования JSON и HTTP не доказывают сохранение профиля в БД.


## Пользовательский контекст — Spec 014 T004

ProfileAuthenticationFilter подключён один раз внутри Spring Security, перед
проверкой доступа. Для точных GET/PUT /api/v1/accounts/me он извлекает UUID из
sub и известные роли USER/MODERATOR/ADMIN/SUPER_ADMIN. Нет ролей или список пуст —
пользователь остаётся аутентифицированным без authorities, USER не добавляется.
Неизвестные строки ролей игнорируются, повреждённые claims отклоняются с единым 401.
Контекст очищается после выполнения запроса, включая ошибки.

Эти две операции требуют authenticated(); management-разрешения сохранены,
остальные маршруты запрещены. Имя зависимости обработчика — securityExceptionHandler.
Accounts доверяет проверке JWT и сессии Gateway; фильтр не проверяет подпись
или личность HTTP caller. Это локальное допущение, не техническая защита от обхода Gateway.
Существующий gRPC mTLS сохраняется.

ProfileAuthenticationSecurityTest проверяет production-цепочку Security с настоящим
GET/PUT-контроллером и mock ProfileService. Тестовых PUT-контроллеров больше нет.
ProfileAuthenticationFilterTest проверяет разбор claims, очистку и изоляцию
параллельных запросов. Эти проверки не заменяют интеграционные тесты БД.


## Чтение своего профиля — Spec 014 T005

GET `/api/v1/accounts/me` получает UUID из SecurityContext. ProfileService читает
существующую запись по user_id и возвращает восемь полей ProfileResponse,
включая явные null. Ответ содержит Cache-Control: no-store. GET не создаёт
профиль и не меняет version или updated_at.

Отсутствующий профиль и временная недоступность БД дают 503 profile_unavailable.
Обработчик пишет request-id и безопасную причину: profile_missing либо тип
исключения. Текст исключения БД и данные профиля в этот лог не передаются.
Непредвиденные ошибки SQL не маскируются под временную недоступность.

ProfileReadIntegrationTest проверяет настоящий GET через Spring Security и MVC
с отдельным PostgreSQL Testcontainers: поля, null, выбор владельца, отсутствие
записей при чтении, отсутствующий профиль и остановку тестовой БД. Пользовательская
БД для этих проверок не используется. Сквозная проверка через Gateway выполнена в T009; сценарий описан в [README Gateway](../gateway-service/README.md#сквозная-проверка).


## Нормализация и валидация — Spec 014 T006

ProfileUpdateNormalizer применяет strip к nickname, firstName, lastName и bio;
пустые необязательные строки становятся null. Остальные поля и внутренние
пробелы сохраняются. Затем ProfileUpdateValidator проверяет все восемь полей
без обращения к БД и собирает ошибки field/code в ProfileValidationException.
ApiExceptionHandler возвращает их как 422 validation_error, не раскрывая значения.

Длина строк считается в Unicode-кодовых точках, согласованно с VARCHAR PostgreSQL.
Имена допускают буквы разных языков, связанные с ними знаки, пробелы, дефис и
апострофы; хотя бы одна буква обязательна. U+0000 запрещён во всех текстовых полях.
Дата рождения проверяется относительно UTC через внедрённый Clock; границы
сегодня и сегодня минус 150 лет включены. ClockConfiguration задаёт Clock.systemUTC.
Timezone должен входить в ZoneId.getAvailableZoneIds(); произвольные смещения
и строки с пробелами по краям не нормализуются в допустимую зону.

Unit-тесты используют фиксированные часы. ProfileHttpContractTest вызывает
настоящие контроллер, сервис, нормализатор и валидатор, проверяя 400/422,
агрегацию ошибок, requestId/no-store и отсутствие вызова записи при ошибке.
Успешный вызов записи подменён mock; реальная БД проверяется отдельно.


## Обновление профиля — Spec 014 T007

ProfileService сохраняет GET и подготавливает запрос обновления вне транзакции.
ProfileUpdateService выполняет native UPDATE RETURNING через ProfileRepository
в @Transactional(timeout = 2)-транзакции. Результат читается через отдельную
UpdateProfileProjection; ноль строк различается как отсутствующий профиль либо
конфликт версии. ProfileVersionConflictException имеет HTTP-обработчик 409.
Работа JPA-запроса и rollback подтверждены ProfileUpdateIntegrationTest.

Конфликт индекса user_profiles_nickname_unique с SQLSTATE 23505 переводится
после rollback в NicknameAlreadyTakenException и HTTP 409 nickname_already_taken.
Другие ошибки целостности сохраняются. На updateProfile нет @Modifying:
UPDATE RETURNING возвращает проекцию, а не количество изменённых строк.

PUT /api/v1/accounts/me принимает application/json, берёт UUID только из
SecurityContext, возвращает ProfileResponse после commit с no-store.
ProfileUpdateIntegrationTest проверяет настоящий HTTP PUT/GET через MockMvc,
production Security и PostgreSQL: сохранение и нормализацию, null, версию,
чужой X-User-Id, оба 409, 400/401/415/422/503, отсутствие записи при отказе,
rollback и остановку одноразовой тестовой БД. Gateway в эти тесты не входит.

ProfileConcurrencyIntegrationTest дополняет эти проверки на отдельном PostgreSQL:
два параллельных сохранения с одной версией, два владельца с одним ником без
учёта регистра, сохранение своего ника, смена регистра и освобождение старого
ника. Проверяются все поля победителя и неизменность проигравшей записи.
Повтор после потерянного ответа моделируется повторной отправкой уже сохранённой
формы со старой версией, в том числе после следующего изменения профиля;
реальный обрыв HTTP-соединения этот тест не воспроизводит.
Для этого класса Docker обязателен: отсутствие Docker приводит к ошибке,
а не к пропуску проверок.

По решению пользователя для текущего объёма достаточно @Transactional(timeout = 2).
Дополнительные настройки пула, statement_timeout/lock_timeout и проверки их
срабатывания исключены из Spec 014; отдельной отложенной задачи нет.
В тесте остановки БД локально ограничено ожидание пула; это не настройка приложения.
