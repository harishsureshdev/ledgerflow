# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY ledgerflow-app/pom.xml ledgerflow-app/
COPY sim-processor/pom.xml sim-processor/
# Pre-fetch dependencies
RUN mvn -B -pl ledgerflow-app dependency:go-offline || true

COPY ledgerflow-app/src ledgerflow-app/src
RUN mvn -B -pl ledgerflow-app clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/ledgerflow-app/target/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
