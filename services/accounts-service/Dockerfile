FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY contracts/grpc-contracts/pom.xml contracts/grpc-contracts/pom.xml
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
COPY services/coins-service/pom.xml services/coins-service/pom.xml
COPY contracts/proto contracts/proto
COPY contracts/grpc-contracts contracts/grpc-contracts
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am dependency:go-offline

COPY services/accounts-service services/accounts-service
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/services/accounts-service/target/accounts-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ==============================================================================
#                        ШПАРГАЛКА ПО DOCKERFILE
# ==============================================================================
#
# 1. ЗАЧЕМ НУЖЕН DOCKER, ЕСЛИ ЕСТЬ ПРОСТО .JAR?
# Файл .jar — это просто "двигатель" (скомпилированный код). Чтобы он завелся,
# ему нужна подходящая среда: установленная Java нужной версии (25), настроенные
# переменные окружения, права доступа.
# Docker-образ — это "готовая машина целиком". Внутри неё уже лежит идеальная
# среда (JRE) + ваш .jar. На сервере (где стоит Docker Engine) не нужно ничего
# настраивать, он просто берет этот образ и запускает.
#
# 2. ПОЧЕМУ ТУТ ДВА РАЗА НАПИСАНО "FROM"? (Multi-stage build)
# Это современная магия, чтобы образ весил мало и был безопасным.
# - Первый FROM (этап build) — это "грязный цех". Мы берем тяжелый JDK (2 ГБ),
#   чтобы скомпилировать наш код в .jar.
# - Второй FROM — это "чистый шоурум". Мы берем легкий JRE (~200 МБ),
#   забираем из первого этапа ТОЛЬКО готовый .jar, а весь мусор (исходники,
#   Maven, JDK) выкидываем. В итоге получаем маленький и быстрый образ.
#
# 3. МАГИЯ КОМАНДЫ COPY (Почему где-то ./ а где-то нет)
# - COPY .mvn .mvn  -> Копируем ПАПКУ. Docker создает папку .mvn внутри /workspace.
# - COPY mvnw pom.xml ./ -> Копируем ФАЙЛЫ. Точка ./ означает "положи их просто в
#   текущую папку", так как файлы не могут создавать папки сами по себе.
# ==============================================================================


# ==============================================================================
#                 РАЗБОР КАЖДОЙ СТРОЧКИ (ПОШАГОВО)
# ==============================================================================

# --- ЭТАП 1: СБОРОЧНЫЙ ЦЕХ (Компилируем код) ---

# Берем образ с полным JDK 25. Называем этот этап "build", чтобы потом ссылаться на него.
# FROM eclipse-temurin:25-jdk AS build

# Создаем рабочую папку /workspace внутри контейнера и переходим в нее (как cd).
# WORKDIR /workspace

# КОПИРУЕМ ПАПКУ Maven Wrapper. (Нужна для запуска сборки).
# COPY .mvn .mvn

# КОПИРУЕМ ФАЙЛЫ Maven Wrapper (скрипт запуска) и главный pom.xml.
# Точка ./ в конце означает "положи эти файлы в текущую папку (/workspace)".
# COPY mvnw pom.xml ./

# ХИТРОСТЬ DOCKER (Кэширование):
# Сначала копируем ТОЛЬКО файлы pom.xml (главный и всех модулей).
# Это нужно, чтобы Docker закэшировал скачивание зависимостей.
# Если мы поменяем код в src, Docker не будет скачивать библиотеки заново.
# COPY contracts/grpc-contracts/pom.xml contracts/grpc-contracts/pom.xml
# COPY services/identity-service/pom.xml services/identity-service/pom.xml
# COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
# COPY services/coins-service/pom.xml services/coins-service/pom.xml

# Копируем общие контракты (proto-файлы), так как они нужны для сборки.
# COPY contracts/proto contracts/proto
# COPY contracts/grpc-contracts contracts/grpc-contracts

# Скачиваем все зависимости заранее (go-offline).
# -pl services/accounts-service — собираем только этот сервис.
# -am (also-make) — но если у него есть зависимости (например, контракты), собери и их тоже.
# RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am dependency:go-offline

# А вот ТЕПЕРЬ (после скачивания библиотек) копируем исходный код самого сервиса.
# COPY services/accounts-service services/accounts-service

# Собираем .jar файл.
# -DskipTests — пропускаем тесты (мы их уже прогнали в GitHub Actions / CI).
# Здесь нам нужно просто быстро собрать бинарник.
# RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am package -DskipTests


# --- ЭТАП 2: ЧИСТЫЙ ШОУРУМ (Запускаем приложение) ---

# Начинаем новый этап с чистого листа. Берем легкий образ ТОЛЬКО с JRE.
# FROM eclipse-temurin:25-jre

# Создаем папку /app и переходим в нее.
# WORKDIR /app

# ГЛАВНАЯ МАГИЯ: Берем готовый .jar из первого этапа (флаг --from=build)
# и кладем сюда, переименовывая в app.jar.
# COPY --from=build /workspace/services/accounts-service/target/accounts-service-*.jar app.jar

# Сообщаем Docker, что контейнер будет слушать порт 8081 (это документация для запуска).
# EXPOSE 8081

# Команда, которая выполнится при запуске контейнера: запустить Java и наш .jar.
# ENTRYPOINT ["java", "-jar", "/app/app.jar"]
