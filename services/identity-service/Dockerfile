FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY contracts/grpc-contracts contracts/grpc-contracts
COPY contracts/proto contracts/proto
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
COPY services/coins-service/pom.xml services/coins-service/pom.xml
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/identity-service -am dependency:go-offline

COPY services/identity-service services/identity-service
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/identity-service -am package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/services/identity-service/target/identity-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
