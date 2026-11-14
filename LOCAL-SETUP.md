# Локальный запуск

## Шаг 1. Что установить на Windows

1. **Git for Windows** — чтобы скачать проект. Для обычного ПК выбери **x64 Setup**. После установки открой новый PowerShell и выполни `git --version`. [Скачать Git](https://git-scm.com/install/windows)
2. **Java 25 LTS — Eclipse Temurin JDK 25.0.5**. Это версия, указанная в проекте. Для обычного ПК выбери **Windows x64, JDK, `.msi`**. В установщике включи добавление в `PATH` и настройку `JAVA_HOME`. В новом PowerShell проверь `java -version` и `javac -version`. Обе команды должны показать `25.0.5`. [Скачать Temurin JDK 25.0.5](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8)
3. **IntelliJ IDEA 2026.3** — для работы с Java-кодом. У JetBrains версия называется `2026.3`. Открой в IDEA корневую папку проекта и выбери установленный Temurin 25 в **Project SDK**. Сборка через PowerShell доступна и без IDE. [Скачать IntelliJ IDEA 2026.3](https://www.jetbrains.com/idea/download/other/)
4. **Docker Desktop** — для контейнерной инфраструктуры и интеграционных тестов. На Windows выбери вариант с **WSL 2**. После установки запусти Docker Desktop и проверь `docker version` и `docker compose version`. У `docker version` должны появиться разделы `Client` и `Server`. [Скачать Docker Desktop и посмотреть требования к WSL 2](https://docs.docker.com/desktop/setup/install/windows-install/)
5. **PostgreSQL 17.x** — да, для нашей локальной настройки выбирай ветку **17**. Установи сервер прямо в Windows. Оставь порт `5432`, если он свободен, и сохрани пароль администратора `postgres`. Один сервер PostgreSQL обслуживает отдельные базы сервисов. В интеграционных тестах проекта тоже используется PostgreSQL 17. [Скачать PostgreSQL для Windows через официальный установщик EDB](https://www.postgresql.org/download/windows/)
6. **DBeaver Community** — установи, чтобы пройти нашу инструкцию создания баз и пользователей. В DBeaver создай подключение к `localhost:5432` под `postgres` и нажми **Test Connection**. DBeaver нужен для настройки и просмотра баз. Сервисы подключаются к PostgreSQL напрямую. [Скачать DBeaver Community](https://dbeaver.com/edition/community/) · [Инструкция по базам в проекте](docs/database-setup.md)

**Maven отдельно устанавливать не нужно.** В репозитории уже есть Maven Wrapper. После клонирования его можно проверить из корня проекта командой `.\mvnw.cmd -v`. Spring Boot 4.1.2 подтянется как зависимость проекта. [README проекта](README.md)

### Если разработчик запускает Android-приложение

7. **Flutter SDK**. В записях проекта указана проверенная версия **3.47.2 stable**. Добавь папку `flutter\bin` в `PATH`. [Установка Flutter](https://docs.flutter.dev/install/manual) · [Запись о проверенной версии](specs/015-mobile-profile/tasks.md)
8. **Android Studio и Android SDK**. В Android Studio установи SDK и Platform-Tools. Если запуск будет на эмуляторе, создай устройство в **Device Manager**. В PowerShell проверь окружение через `flutter doctor -v`. [Скачать Android Studio](https://developer.android.com/studio/) · [Настройка Android для Flutter](https://docs.flutter.dev/platform-integration/android/setup)

## Шаг 2. Скачать проект

В PowerShell выполните:

```powershell
git clone https://github.com/returnaction/fitness-platform.git
cd fitness-platform
```

## Шаг 3. Открыть проект в IntelliJ IDEA и выбрать JDK

1. Нажмите **File → Open** и выберите папку `fitness-platform`, в которой лежат `pom.xml` и `mvnw.cmd`.
2. Откройте **File → Project Structure → Project**. В Windows можно нажать `Ctrl+Alt+Shift+S`.
3. В поле **SDK** выберите **Eclipse Temurin 25.0.5**. Если установлено несколько JDK, сверяйте название **Temurin** и версию **25.0.5**.
4. Если нужного JDK в списке нет, нажмите **Add JDK from disk** и выберите папку установленного JDK, например `C:\Program Files\Eclipse Adoptium\jdk-25.0.5+8`. Это папка, внутри которой находится `bin`.
5. Нажмите **Apply → OK**. Справа откройте панель **Maven** и нажмите **Reload All Maven Projects**.

В PowerShell из папки проекта выполните:

```powershell
.\mvnw.cmd -v
```

В выводе должна быть версия Java **25.0.5**. Если показана другая версия, укажите папку Temurin 25.0.5 в переменной Windows `JAVA_HOME`, откройте новый PowerShell и повторите команду. Настройка SDK в IDEA и выбор Java в PowerShell проверяются отдельно.

## Шаг 4. Подготовить и запустить серверы

Все команды PowerShell выполняйте из корня проекта, где лежит `mvnw.cmd`.
Для входа и регистрации в мобильном приложении запустите четыре сервиса:
Accounts, Coins, Identity и Gateway.

### 4.1. Создать .env, сертификаты gRPC и ключи JWT

```powershell
.\scripts\initialize-local-env.ps1
```

Скрипт создаёт недостающие `.env` из шаблонов для всех сервисов. Через
OpenSSL он создаёт в `secrets` сертификаты и ключи gRPC для mTLS между
сервисами и отдельную пару ключей для подписи JWT в Identity. Пути к файлам
уже есть в новых `.env`.

Срок действия сертификатов gRPC — 30 дней. Их продление общим скриптом
пока не реализовано.

### 4.2. Создать базы и вписать пароли

В DBeaver выполните шаги 1–5 из [инструкции по базам](docs/database-setup.md)
для Accounts, Coins и Identity. Для каждого сервиса создайте роль и базу,
выполните команды настройки прав, затем откройте `.env` этого сервиса.
В существующей строке `DATABASE_PASSWORD` замените значение после `=`
на пароль роли, которую только что создали. Gateway база не нужна.

### 4.3. Собрать сервисы

```powershell
.\mvnw.cmd -pl services/accounts-service,services/coins-service,services/identity-service,services/gateway-service -am -DskipTests package
```

Дождитесь `BUILD SUCCESS`. Эта команда собирает JAR для первого запуска
и пропускает выполнение тестов. Файл `test-environment.properties`
уже есть в Git и используется тестами. Для запуска с профилем `local`
его заполнять не нужно.

### 4.4. Запустить сервисы

Выполните каждую команду в отдельном окне PowerShell из корня проекта.
Оставляйте окна открытыми и дожидайтесь запуска сервиса перед следующей командой.

```powershell
java -jar services/accounts-service/target/accounts-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/coins-service/target/coins-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Откройте [проверку Gateway](http://127.0.0.1:9088/actuator/health).
Ожидаемый статус — `UP`.

### 4.5. Запустить Android-приложение

Откройте эмулятор, перейдите в `apps/mobile` и выполните `flutter run`.
Для телефона используйте [инструкцию подключения по USB](apps/mobile/README.md).

### Остальные скрипты

Для шагов выше достаточно `initialize-local-env.ps1`.

- `verify-registration-e2e.py` проверяет сценарий регистрации.
- `verify-markdown-links.ps1` проверяет ссылки в документации.
