# Сборка выполняется внутри контейнера с той же версией JDK, что и проект.
FROM eclipse-temurin:25.0.5_8-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY contracts/grpc-contracts/pom.xml contracts/grpc-contracts/pom.xml
# Maven читает POM каждого модуля, перечисленного в корневом pom.xml.
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/gateway-service/pom.xml services/gateway-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
COPY services/coins-service/pom.xml services/coins-service/pom.xml
COPY services/challenge-service/pom.xml services/challenge-service/pom.xml
COPY services/video-proof-service/pom.xml services/video-proof-service/pom.xml
COPY services/moderation-service/pom.xml services/moderation-service/pom.xml
COPY services/exercise-catalog-service/pom.xml services/exercise-catalog-service/pom.xml
COPY services/notifications-service/pom.xml services/notifications-service/pom.xml
COPY services/exercise-catalog-service/src services/exercise-catalog-service/src
# Wrapper запускается через bash: в Git файл не обязан иметь права на исполнение.
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/exercise-catalog-service -am package -DskipTests

# В итоговый образ попадают только JRE и готовое приложение.
FROM eclipse-temurin:25.0.5_8-jre
WORKDIR /app
COPY --from=build /workspace/services/exercise-catalog-service/target/exercise-catalog-service-*.jar app.jar
# EXPOSE документирует пример порта, но не задаёт SERVER_PORT.
EXPOSE 8086
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
