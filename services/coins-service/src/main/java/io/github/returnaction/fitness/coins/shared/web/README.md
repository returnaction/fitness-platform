# Как HTTP-запрос проходит через Coins

Пример: `GET /api/v1/coins/daily-reward/status` с Bearer-токеном.
Ниже показаны наши компоненты; служебные фильтры Spring для простоты опущены.

```text
Gateway → Coins
    ↓
0. RequestIdFilter — назначает requestId для ответа и логов
    ↓
1. CoinsAuthenticationFilter — достаёт пользователя и роли из JWT
    ↓
2. Spring Security — проверяет, разрешён ли доступ к этому URL и HTTP-методу
    ↓
3-A. NoStoreResponseInterceptor — запрещает кешировать ответ
    ↓
3-B. NoQueryParametersInterceptor — запрещает параметры после «?»
    ↓
3-C. Spring MVC подготавливает аргументы и вызывает контроллер
    ↓
После обработки: 4-A. CoinsAuthenticationFilter очищает SecurityContext
Затем RequestIdFilter закрывает MDC-контекст логирования
```

## Где задаётся порядок

Конфигурации выполняются **при запуске приложения**: они подключают обработчики.
При каждом запросе работают уже подключённые фильтры и interceptors.

| Где смотреть | Что регистрируется |
| --- | --- |
| [ServletFiltersConfiguration](configuration/ServletFiltersConfiguration.java) | RequestIdFilter для `/*`, раньше Spring Security |
| [SecurityConfiguration](../security/SecurityConfiguration.java) | CoinsAuthenticationFilter внутри цепочки Security и правила доступа |
| [CoinsWebConfiguration](configuration/CoinsWebConfiguration.java) | MVC-interceptors: сначала no-store, затем проверка параметров |

Фрагменты регистрации из этих классов:

```java
// ServletFiltersConfiguration: обычный servlet-фильтр.
var registration = new FilterRegistrationBean<>(new RequestIdFilter());
registration.addUrlPatterns("/*");
registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

// SecurityConfiguration: фильтр внутри Spring Security.
http.addFilterBefore(new CoinsAuthenticationFilter(securityExceptionHandler),
    UsernamePasswordAuthenticationFilter.class);

// CoinsWebConfiguration: пример регистрации для одного endpoint.
registry.addInterceptor(new NoStoreResponseInterceptor())
    .order(0).addPathPatterns("/api/v1/coins/daily-reward/status");
registry.addInterceptor(new NoQueryParametersInterceptor())
    .order(1).addPathPatterns("/api/v1/coins/daily-reward/status");
```

Меньшее число означает более раннее выполнение **внутри одного уровня**.
`order(0)` interceptor не делает его раньше servlet-фильтров.
Фильтр нельзя одновременно подключать через `@Component` и FilterRegistrationBean:
поэтому у RequestIdFilter нет `@Component` и `@Order`.

## Что происходит внутри фильтра аутентификации

Открой [CoinsAuthenticationFilter](../security/CoinsAuthenticationFilter.java).

1. `shouldNotFilter()` решает, пропустить ли наш фильтр. Для `/api/v1/coins/…`
   возвращает `false`: нужно выполнять. Для `/actuator/health` — `true`:
   извлекать пользователя не нужно, запрос идёт дальше по цепочке.
2. `doFilterInternal()` вызывает `readAuthentication()`: читаются Bearer,
   UUID пользователя из `sub` и роли через `readAuthorities()`.
3. Пользователь помещается в `SecurityContext`. Потом контроллер сможет
   получить его через `@AuthenticationPrincipal`.
4. `filterChain.doFilter()` передаёт запрос дальше. **Фильтр ждёт возврата
   из дальнейшей обработки**, а не заканчивает работу в этой строке.
5. После возврата выполняется `finally`: контекст пользователя очищается,
   даже если дальше произошла ошибка.

Здесь JWT только разбирается: подпись, срок действия, issuer и audience этот
фильтр не проверяет. Он рассчитан на предварительную проверку Gateway.
Защита от прямого входа в Coins в обход доверенного Gateway — отдельное
требование; наличие этого фильтра её не доказывает.

## Если запрос не прошёл проверку

**Не удалось получить пользователя из токена:**

```text
CoinsAuthenticationFilter ловит ошибку
    → SecurityExceptionHandler.commence()
    → resolve() → Spring HandlerExceptionResolver
    → ApiExceptionHandler.handleAuthentication()
    → 401 с JSON ошибки; контроллер не вызывается
```

Для аутентифицированного пользователя без доступа Spring Security вызывает
`SecurityExceptionHandler.handle()`: тот же путь приводит к `403`.
[SecurityExceptionHandler](../security/SecurityExceptionHandler.java) — обработчик
ошибок, а не следующий фильтр. `permitAll()` разрешает доступ, но само по себе
не отключает пользовательские фильтры.

**Пользователь прошёл Security, но прислал `status?foo=bar`:**

```text
NoStoreResponseInterceptor ставит no-store
    → NoQueryParametersInterceptor выбрасывает InvalidRequestException
    → ApiExceptionHandler возвращает 400; контроллер не вызывается
```

В этом случае SecurityExceptionHandler не участвует: это ошибка HTTP-контракта,
а не аутентификации. При выходе из цепочки контекст пользователя всё равно очищается.

`no-store` устанавливается и в обработчиках ошибок, потому что ошибка может
возникнуть до MVC-interceptors. Повторный `setHeader` заменяет значение,
а не добавляет второй такой же заголовок.

## Если добавляешь новый endpoint

- Задай доступ в SecurityConfiguration: остальные запросы сейчас запрещены.
- Отдельно реши, нужны ли no-store и запрет query-параметров. Списки путей
  в CoinsWebConfiguration независимы; новый URL не добавляется в них автоматически.
- Например, история с `?page=1` может требовать no-store, но должна разрешать параметры.
- Разрешённое кеширование задавай по контракту явно: отсутствие нашего interceptor
  не отменяет настройки заголовков Spring Security или Gateway.

Для просмотра пути в IDEA ставь breakpoints в `doFilterInternal`, `shouldNotFilter`
и `preHandle`, а не только в конфигурациях. На `filterChain.doFilter()` нажимай
Resume, чтобы перейти к следующей своей точке, не проходя внутренности Spring.
