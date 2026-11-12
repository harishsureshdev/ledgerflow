# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY ledgerflow-app/pom.xml ledgerflow-app/
COPY sim-processor/pom.xml sim-processor/
# Pre-fetch dependencies
RUN mvn -B -pl sim-processor dependency:go-offline || true

COPY sim-processor/src sim-processor/src
RUN mvn -B -pl sim-processor clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/sim-processor/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
