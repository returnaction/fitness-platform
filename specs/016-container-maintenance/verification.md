# Проверка Spec 016 — 2026-11-13

## Среда и команды

Windows, Docker Desktop (Linux containers), отдельный worktree `42-maintenance`.
Все девять Dockerfile собраны из корня репозитория существующим способом:

```powershell
$services = Get-ChildItem services -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'Dockerfile') }
foreach ($service in $services) {
    docker build --progress=plain -f "services/$($service.Name)/Dockerfile" -t "spec016/$($service.Name):verify" .
}
docker run --rm --network none --entrypoint sh eclipse-temurin:25.0.5_8-jdk -c 'java -version; javac -version'
```

JDK сообщил `25.0.5+8-LTS`, javac — `25.0.5`. Все Dockerfile используют этот
сборочный образ и `eclipse-temurin:25.0.5_8-jre` для финального этапа.
Для каждого готового образа проверены `docker image inspect` (Config.User),
`id -u`, `java -version`, чтение `/app/app.jar`, отсутствие права записи в JAR
и доступность `/tmp` для записи. Пользователь запуска не переопределялся.
У запущенного приложения проверялся `/proc/1/status`: все четыре UID равны 10001.

## Условия запуска

- Профиль `production`, конфигурация из тестовых properties с заменой адресов,
  портов и секретов на параметры временного окружения; пользовательский `.env` не читался.
- Отдельный временный кластер установленного PostgreSQL 17 вне Docker;
  базы `spec016_identity`, `spec016_accounts`, `spec016_coins` созданы только
  для этой проверки. Liquibase выполнялся только в этих новых базах.
  Рабочие пользовательские БД не использовались.
- Одноразовые тестовые ключи и сертификаты созданы существующим помощником
  `scripts/verify-registration-e2e.py`. Переданы через read-only volume,
  владелец 10001, каталог 0700, файлы 0400. В образы они не копировались.
  Подготовительный контейнер выставлял права от root; сами приложения — UID 10001.
- HTTP и management опубликованы на случайных портах localhost. Только внутри
  проверочных контейнеров соответствующие listener настроены на 0.0.0.0.
  Gateway загружал JWKS из запущенного Identity по HTTP.
- Проверялся HTTP 200 `/actuator/health/readiness` каждого сервиса. У Gateway
  эта группа включает JWKS. Чтение необходимых настроек и ключей подтверждалось
  успешным стартом; полноценные прикладные gRPC-вызовы этим не проверялись.

## Результаты

| Сервис | Сборка | Java | UID процесса | Readiness |
| --- | --- | --- | --- | --- |
| accounts-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| challenge-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| coins-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| exercise-catalog-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| gateway-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| identity-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| moderation-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| notifications-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |
| video-proof-service | Успех | 25.0.5+8 | 10001 | HTTP 200 |

У всех девяти образов JAR читается и недоступен для записи, /tmp доступен для записи.
После прогона проверочные контейнеры и volumes удалены; `pg_ctl status` подтвердил
остановку временного PostgreSQL. Собранные образы оставлены локально для ревью.

## Границы проверки

Сборка Dockerfile выполняет Maven `package -DskipTests`: это успешная сборка,
а не запуск полного набора unit/integration-тестов или отдельного static analysis.
Форматтер не запускался. Бизнес-код и API не менялись.
Проверка относится к локальным образам и указанному окружению. Серверный deploy,
его сеть и права реальных секретов не проверялись и не объявляются готовыми.

Промежуточные ошибки проверочного скрипта (Windows pipes, параметры management,
кодировка UTF-8) исправлялись только в локальном `target/spec016`; исходники
приложений для прохождения проверки не менялись. Локальные build/runtime-логи
и JSON-результаты находятся в игнорируемом `target/spec016`.
