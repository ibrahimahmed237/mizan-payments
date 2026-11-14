# One image for the service or the simulator. Build the jars first: ./mvnw package -DskipTests
# The jar to run is given as a build argument; docker-compose.yml does that for both.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S mizan && adduser -S mizan -G mizan
WORKDIR /app
ARG JAR
COPY ${JAR} app.jar
USER mizan
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
