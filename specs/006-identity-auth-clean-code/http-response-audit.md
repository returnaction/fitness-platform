# Сверка HTTP-ответов login и refresh

Проверены текущий OpenAPI, прежние контроллеры из `HEAD` и тесты Identity.
Согласованное изменение: режим запуска без JWT убран; прежний вариант ошибки
при выключенном JWT больше не является рабочим сценарием.

| Сценарий | Ответ и проверка |
| --- | --- |
| Успешный login / refresh | `200`, пара токенов и `Cache-Control: no-store`; интеграционные тесты входа и ротации. |
| Неверные учётные данные / refresh | `401`, `invalid_credentials` / `invalid_refresh_token`, `no-store`, сообщение и `request_id`; HTTP-тест и тест обработчика. |
| PENDING | `503 registration_pending`, без токенов; HTTP-тест. |
| Аккаунт недоступен | `403 account_unavailable`, отдельные прежние сообщения для login и refresh, `no-store` и `request_id`; HTTP-тест на PostgreSQL. |
| Нет JSON или JSON повреждён | `400 invalid_request`, `request_id`; HTTP-тест для обоих методов. |
| Некорректные поля | `422 validation_error`; тесты login и refresh, включая отсутствующий, пустой и слишком длинный refresh. |
| Неподдерживаемые Accept / Content-Type | `406` без тела / `415 unsupported_media_type`; HTTP-тесты обоих методов. |
| Превышен лимит | `429` с `Retry-After`; проверены отдельные лимиты login и refresh. |
| Нет ролей / ошибка БД | `500 internal_error` / `503 identity_unavailable`; интеграционные тесты без частичной ротации refresh. |
| Истёкший или повторно использованный refresh | `401 invalid_refresh_token`; на PostgreSQL проверены отсутствие отзыва для истёкшего и отзыв при повторе использованного токена. |

OpenAPI для `422` refresh ссылался на общее описание ошибок email и пароля;
ссылка заменена на описание валидации `refresh_token`, формат ответа не изменён.
Имя поля в `details` для refresh сейчас `refreshToken`: оно сохраняет прежнее
поведение, хотя имя во входном JSON — `refresh_token`.

10 ноября 2026 прошли 43 теста HTTP и обработчика, а также отдельный тест
недоступного аккаунта с изолированной PostgreSQL. YAML OpenAPI разобран
SnakeYAML, новая ссылка на компонент проверена; полная валидация схемы OpenAPI
отдельным валидатором не выполнялась.
