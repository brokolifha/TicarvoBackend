FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src/main ./src/main
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25.0.4.1_1-jre-noble
RUN groupadd --gid 10001 ticarvo && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin ticarvo
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/ticarvo-backend-*.jar /app/ticarvo-backend.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ticarvo-backend.jar"]
