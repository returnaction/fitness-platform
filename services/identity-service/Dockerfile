FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY contracts/grpc-contracts contracts/grpc-contracts
COPY contracts/proto contracts/proto
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/gateway-service/pom.xml services/gateway-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
COPY services/coins-service/pom.xml services/coins-service/pom.xml
COPY services/challenge-service/pom.xml services/challenge-service/pom.xml
COPY services/video-proof-service/pom.xml services/video-proof-service/pom.xml
COPY services/moderation-service/pom.xml services/moderation-service/pom.xml
COPY services/exercise-catalog-service/pom.xml services/exercise-catalog-service/pom.xml
COPY services/notifications-service/pom.xml services/notifications-service/pom.xml
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/identity-service -am dependency:go-offline

COPY services/identity-service services/identity-service
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/identity-service -am package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/services/identity-service/target/identity-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
