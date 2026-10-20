# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/backend-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home jobmatcher
USER 10001
COPY --from=build /app.jar /app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["java", "-jar", "/app.jar"]
