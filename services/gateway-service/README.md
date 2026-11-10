# Gateway Service

Единая точка входа auth API. [Контракт v1](../../contracts/gateway-v1.md)
описывает маршруты, авторизацию, заголовки, лимиты и ошибки.
Spring Cloud BOM 2025.1.4 выбирает Gateway Server MVC 5.0.4;
Java 25.0.5 и Spring Boot 4.1.2 наследуются от корневого POM.
Gateway не владеет БД и не запускает Liquibase.

## Локальный запуск

1. Соберите `mvnw.cmd -pl services/gateway-service -am verify`.
2. Скопируйте `.env.example` этого сервиса в его `.env`. Задайте порты,
   issuer/audience, совпадающие с Identity, и адрес JWKS Identity.
3. Выпустите отдельный клиентский сертификат от той же CA, которой доверяет
   session gRPC Identity. URI SAN должен быть
   `spiffe://fitness.local/gateway-service`, EKU — clientAuth. Впишите пути
   сертификата, PKCS8 private key и CA в GATEWAY_CERTIFICATE,
   GATEWAY_PRIVATE_KEY и GATEWAY_TRUST_CERTIFICATE. Секреты храните вне Git.
4. В окружении Identity задайте
   `IDENTITY_SESSION_GRPC_ALLOWED_CALLER=spiffe://fitness.local/gateway-service`
   и `IDENTITY_REGISTRATION_TRUSTED_PROXY_IP=127.0.0.1`; session gRPC должен
   слушать loopback. Сертификат gRPC-сервера должен содержать DNS SAN localhost.
   Адрес GATEWAY_SESSION_HOST должен совпадать с SAN, проверка имени не отключается.
5. Запустите Identity, Accounts и Coins по их инструкциям, затем из корня:
   `java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar`.

По шаблону API — `http://127.0.0.1:8088`, management —
`http://127.0.0.1:8089/actuator/health`. Секреты и локальный `.env` не создаются
автоматически и не перезаписываются. Некорректные обязательные настройки или
отсутствующие сертификаты останавливают запуск. Недоступность Identity после
запуска даёт временный отказ; токены при этом не считаются отозванными.

В local Identity привязан к 127.0.0.1. Другой компьютер не должен иметь прямого
доступа к его HTTP/gRPC. Локальный пользователь и его процессы считаются доверенными;
изолировать от них Identity одним loopback невозможно. Это не production-схема.
Приложение Flutter не переключено; реальный телефон пока не является клиентом
этого локального стенда. Разрешающих CORS origins нет.

## Проверки

- `mvnw.cmd -pl services/gateway-service -am verify`: HTTP proxy, JWT/JWKS,
  обновление ключей, лимиты, TLS, сбои, Spotless и SpotBugs.
- `mvnw.cmd verify`: весь проект, включая тесты Identity на временной PostgreSQL.
- После package четырёх сервисов:
  `python scripts/verify-registration-e2e.py --gateway --postman`.
  Нужны Docker для временных тестовых БД, OpenSSL, Python, Node.js/npx.
  Newman закреплён как 6.2.3. Это тестовый PostgreSQL, не база локального приложения
  и не PostgreSQL в Docker Compose. Тест создаёт и удаляет собственные ресурсы.
- Импортируйте [существующую коллекцию](../../postman/fitness-local.postman_collection.json)
  в Postman. `base_url` указывает на Gateway; тестовые email и пароль генерируются.
  Отдельная папка outage пропускается по умолчанию: для неё заранее получите
  access-токен, остановите Identity и задайте локальные environment-переменные
  `run_outage=true`, `outage_access_token`. После проверки включите Identity.
  Коллекцию с реальными значениями секретов не экспортировать в Git.

## Эксплуатационные ограничения v1

Один экземпляр, локальные rate limits. Нет автоматических повторов HTTP POST
или CheckSession. HTTP-клиент явно запрещает retries и redirects; потерянный
ответ refresh должен обрабатываться согласно контракту Identity.
Кеш JWKS не разрешает кешировать ACTIVE сессию. При ротации публикуйте старый
и новый открытые ключи на необходимый период, если требуется сохранять старые
токены; текущий Identity публикует один настроенный ключ, поэтому его замена
не обещает бесшовного сохранения старых токенов.
Метрика `gateway.session.check` различает active/invalid/unavailable без user ID.
Management не публикуется через API-порт. Production-развёртывание и несколько
экземпляров требуют отдельного этапа.
