# Multi-stage build: the Maven layer keeps the dependency cache warm without
# shipping the build tooling into the runtime image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies first: this layer only rebuilds when the POM changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Never run the service as root.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /build/target/retail-finance-core-*.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]