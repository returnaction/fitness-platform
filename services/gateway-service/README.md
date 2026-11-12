# Gateway Service

Единая точка входа auth API. [Контракт v1](../../contracts/gateway-v1.md)
описывает маршруты, авторизацию, заголовки, лимиты и ошибки.
Spring Cloud BOM 2025.1.4 выбирает Gateway Server MVC 5.0.4;
Java 25.0.5 и Spring Boot 4.1.2 наследуются от корневого POM.
Gateway не владеет БД и не запускает Liquibase.
