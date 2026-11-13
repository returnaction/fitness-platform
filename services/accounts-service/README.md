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
сохраняется для проверки правил значений будущим ProfileUpdateValidator (T006).
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

Реальные контроллеры GET/PUT и сохранение профиля ещё не реализованы. ProfileHttpContractTest использует отдельный тестовый
контроллер и тестовую цепочку Security, разрешённую только в его контексте.
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

ProfileAuthenticationSecurityTest проверяет production-цепочку Security с тестовым
контроллером, а ProfileAuthenticationFilterTest — разбор claims, очистку и
изоляцию параллельных запросов. Эти тесты не реализуют GET/PUT профиля из T005–T007.
