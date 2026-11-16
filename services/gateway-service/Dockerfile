FROM eclipse-temurin:25.0.5_8-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config config
COPY contracts/grpc-contracts contracts/grpc-contracts
COPY contracts/proto contracts/proto
COPY services/gateway-service services/gateway-service
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
COPY services/coins-service/pom.xml services/coins-service/pom.xml
COPY services/challenge-service/pom.xml services/challenge-service/pom.xml
COPY services/video-proof-service/pom.xml services/video-proof-service/pom.xml
COPY services/moderation-service/pom.xml services/moderation-service/pom.xml
COPY services/exercise-catalog-service/pom.xml services/exercise-catalog-service/pom.xml
COPY services/notifications-service/pom.xml services/notifications-service/pom.xml
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/gateway-service -am package -DskipTests

FROM eclipse-temurin:25.0.5_8-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/fitness-platform/secrets /run/fitness-platform/crypto /var/fitness/dumps \
    && chown -R 10001:10001 /run/fitness-platform /var/fitness
WORKDIR /app
COPY --from=build /workspace/services/gateway-service/target/gateway-service-*.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
