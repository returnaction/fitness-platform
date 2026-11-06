FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY contracts/grpc-contracts/pom.xml contracts/grpc-contracts/pom.xml
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/accounts-service/pom.xml services/accounts-service/pom.xml
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am dependency:go-offline

COPY services/accounts-service services/accounts-service
RUN bash ./mvnw --batch-mode --no-transfer-progress -pl services/accounts-service -am package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/services/accounts-service/target/accounts-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
