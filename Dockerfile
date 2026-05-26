# Etapa 1: Build con Gradle
FROM gradle:8.11-jdk21 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/
COPY src/ ./src/
RUN gradle buildFatJar --no-daemon -x test

# Etapa 2: Imagen ligera para Cloud Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/app.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xmx512m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
