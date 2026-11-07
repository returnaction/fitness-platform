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
# ВНИМАНИЕ! НИЖЕ ИДЕТ ШПАРГАЛКА. ОНА НЕ ВЛИЯЕТ НА РАБОТУ DOCKER,
# ТАК КАК КАЖДАЯ СТРОЧКА НАЧИНАЕТСЯ С СИМВОЛА # (КОММЕНТАРИЙ).
# ==============================================================================

# ==============================================================================
# 1. ЗАЧЕМ НУЖЕН DOCKER, ЕСЛИ ЕСТЬ ПРОСТО .JAR?
# ==============================================================================
# Файл .jar — это просто "двигатель" (скомпилированный код). Чтобы он завелся,
# ему нужна подходящая среда: установленная Java нужной версии (25), настроенные
# переменные окружения, права доступа.
# Docker-образ — это "готовая машина целиком". Внутри неё уже лежит идеальная
# среда (JRE) + ваш .jar. На сервере (где стоит Docker Engine) не нужно ничего
# настраивать, он просто берет этот образ и запускает.

# ==============================================================================
# 2. ПОЧЕМУ ТУТ ДВА РАЗА НАПИСАНО "FROM"? (Multi-stage build)
# ==============================================================================
# Это современная магия, чтобы образ весил мало и был безопасным.
# - Первый FROM (этап build) — это "грязный цех". Мы берем тяжелый JDK (2 ГБ),
#   чтобы скомпилировать наш код в .jar.
# - Второй FROM — это "чистый шоурум". Мы берем легкий JRE (~200 МБ),
#   забираем из первого этапа ТОЛЬКО готовый .jar, а весь мусор (исходники,
#   Maven, JDK) выкидываем. В итоге получаем маленький и быстрый образ.

# ==============================================================================
# 3. МАГИЯ КОМАНДЫ COPY (Почему где-то ./ а где-то нет)
# ==============================================================================
# - COPY .mvn .mvn  -> Копируем ПАПКУ. Docker создает папку .mvn внутри /workspace.
# - COPY mvnw pom.xml ./ -> Копируем ФАЙЛЫ. Точка ./ означает "положи их просто в
#   текущую папку", так как файлы не могут создавать папки сами по себе.


# ==============================================================================
#                 ЭТАП 1: СБОРОЧНЫЙ ЦЕХ (ПОШАГОВЫЙ РАЗБОР)
# ==============================================================================

# --- ШАГ 1: Старт ---
# FROM eclipse-temurin:25-jdk AS build
# Docker скачивает чистую виртуальную машину (Linux), где уже установлена
# Java 25 (JDK). Мы даем этому этапу имя "build", чтобы потом к нему вернуться.

# --- ШАГ 2: Создаем рабочую папку ---
# WORKDIR /workspace
# Мы как будто заходим в терминале в папку /workspace (команда cd).
# Сейчас внутри контейнера пока пусто.
#
# Внутри контейнера:
# /workspace/ (пусто)

# --- ШАГ 3: Копируем папку .mvn ---
# COPY .mvn .mvn
# Docker берет папку .mvn с твоего компьютера и кладет её внутрь контейнера.
#
# Внутри контейнера:
# /workspace/
# └── .mvn/
#     └── wrapper/
#         └── maven-wrapper.properties

# --- ШАГ 4: Копируем файлы mvnw и pom.xml ---
# COPY mvnw pom.xml ./
# Точка ./ означает "положи в текущую папку". Docker кладет два файла прямо в /workspace.
#
# Внутри контейнера:
# /workspace/
# ├── .mvn/ (уже тут)
# ├── mvnw
# └── pom.xml

# --- ШАГ 5: Копируем файлы pom.xml всех модулей ---
# COPY contracts/grpc-contracts/pom.xml contracts/grpc-contracts/pom.xml
# COPY services/identity-service/pom.xml services/identity-service/pom.xml
# COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
# COPY services/coins-service/pom.xml services/coins-service/pom.xml
# Мы копируем только файлы конфигурации (pom.xml), чтобы Docker закэшировал
# библиотеки. Docker сам создаст все нужные папки по пути.
#
# Внутри контейнера:
# /workspace/
# ├── .mvn/
# ├── mvnw
# ├── pom.xml
# ├── contracts/
# │   └── grpc-contracts/
# │       └── pom.xml
# └── services/
#     ├── identity-service/
#     │   └── pom.xml
#     ├── accounts-service/
#     │   └── pom.xml
#     └── coins-service/
#         └── pom.xml

# --- ШАГ 6: Копируем контракты (proto-файлы) ---
# COPY contracts/proto contracts/proto
# COPY contracts/grpc-contracts contracts/grpc-contracts
# Копируем сами proto-файлы (описания API), так как без них Maven не сможет сгенерировать код.
#
# Внутри контейнера:
# /workspace/
# ├── ...
# └── contracts/
#     ├── grpc-contracts/
#     │   ├── pom.xml
#     │   └── (файлы и папки с кодом)
#     └── proto/
#         └── (файлы .proto)

# --- ШАГ 7: Скачиваем зависимости (Интернет) ---
# RUN bash ./mvnw ... dependency:go-offline
# Мы запускаем Maven. Он читает все pom.xml и скачивает нужные библиотеки
# из интернета в папку ~/.m2. Это долгий шаг, но Docker его запомнит (закэширует).
# Если мы потом изменим только код в src, этот шаг будет пропущен (взят из кэша).

# --- ШАГ 8: Копируем исходный код сервиса ---
# COPY services/accounts-service services/accounts-service
# А вот теперь мы копируем папку src (исходники) нашего сервиса.
#
# Внутри контейнера:
# /workspace/services/accounts-service/
# ├── pom.xml (уже тут)
# └── src/
#     └── main/
#         └── java/...

# --- ШАГ 9: Компилируем (Сборка) ---
# RUN bash ./mvnw ... package -DskipTests
# Maven компилирует код и упаковывает его в готовый .jar файл.
#
# Внутри контейнера:
# /workspace/services/accounts-service/target/
# └── accounts-service-1.0.0.jar (готовый файл!)


# ==============================================================================
#                 ЭТАП 2: ЧИСТЫЙ ШОУРУМ (ПОШАГОВЫЙ РАЗБОР)
# ==============================================================================

# --- ШАГ 10: Сброс (Новая машина) ---
# FROM eclipse-temurin:25-jre
# ВНИМАНИЕ! Мы начинаем всё с нуля. Всё, что было в первом этапе (/workspace,
# исходники, Maven, JDK) — стирается. Docker скачивает новый, чистый и легкий
# образ только с JRE (среда выполнения).

# --- ШАГ 11: Рабочая папка ---
# WORKDIR /app
# Заходим в новую папку /app.
#
# Внутри контейнера:
# /app/ (пусто)

# --- ШАГ 12: МАГИЯ! Забираем .jar из прошлого ---
# COPY --from=build /workspace/services/accounts-service/target/accounts-service-*.jar app.jar
# Флаг --from=build говорит: "Загляни в первый этап (build), найди там .jar
# и скопируй его сюда, переименовав в app.jar".
#
# Внутри контейнера:
# /app/
# └── app.jar

# --- ШАГ 13: Документация портов ---
# EXPOSE 8081
# Просто вешаем табличку на контейнер: "Я слушаю порт 8081".

# --- ШАГ 14: Запуск ---
# ENTRYPOINT ["java", "-jar", "/app/app.jar"]
# Говорим: "Когда кто-то запустит этот контейнер, выполни эту команду".
# Java запустит наш app.jar.
