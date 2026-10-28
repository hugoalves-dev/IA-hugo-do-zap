FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main ./src/main
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S -G app -u 10001 app
COPY --from=build --chown=app:app /build/target/whatsapp-archived-watcher-1.0.0-SNAPSHOT.jar /app/app.jar
ENV MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
    MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
