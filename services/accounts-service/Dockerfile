FROM eclipse-temurin:25.0.5_8-jdk AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY contracts/grpc-contracts/pom.xml contracts/grpc-contracts/pom.xml
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/gateway-service/pom.xml services/gateway-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
COPY services/coins-service/pom.xml services/coins-service/pom.xml
COPY services/challenge-service/pom.xml services/challenge-service/pom.xml
COPY services/video-proof-service/pom.xml services/video-proof-service/pom.xml
COPY services/moderation-service/pom.xml services/moderation-service/pom.xml
COPY services/exercise-catalog-service/pom.xml services/exercise-catalog-service/pom.xml
COPY services/notifications-service/pom.xml services/notifications-service/pom.xml
COPY contracts/proto contracts/proto
COPY contracts/grpc-contracts contracts/grpc-contracts
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am dependency:go-offline

COPY services/accounts-service services/accounts-service
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am package -DskipTests

FROM eclipse-temurin:25.0.5_8-jre

WORKDIR /app
COPY --from=build /workspace/services/accounts-service/target/accounts-service-*.jar app.jar
EXPOSE 8081
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
