FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
COPY simulator/pom.xml simulator/pom.xml
COPY backend/src backend/src
COPY simulator/src simulator/src
ARG MODULE
RUN mvn -B -ntp -pl "${MODULE}" -am -Dmaven.test.skip=true package && cp "${MODULE}"/target/*.jar /app.jar

FROM eclipse-temurin:21.0.8_9-jdk-jammy
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
COPY --from=build /app.jar /app/app.jar
COPY infra/HealthCheck.java /opt/health/HealthCheck.java
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
