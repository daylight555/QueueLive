FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./mvnw -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S queue && adduser -S queue -G queue
WORKDIR /app
COPY --from=build /app/target/queuelive-0.1.0.jar app.jar
USER queue
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
