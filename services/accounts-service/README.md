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

Реальные операции GET/PUT и фильтр Bearer ещё не реализованы: production-правило
`denyAll` сохранено. ProfileHttpContractTest использует отдельный тестовый
контроллер и тестовую цепочку Security, разрешённую только в его контексте.
Проверки преобразования JSON и HTTP не доказывают сохранение профиля в БД.
