# Le jar est construit sur l'hôte (scripts/prepare-build) : tests Testcontainers inclus,
# et dépendances Maven résolues avec le magasin de certificats du système.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/atlas-bet-0.1.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
