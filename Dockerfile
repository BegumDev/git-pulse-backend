FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY target/quarkus-app/ ./

USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
