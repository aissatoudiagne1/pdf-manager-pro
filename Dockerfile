FROM maven:3-openjdk-11 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY public ./public
RUN mvn package -q -DskipTests

FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/target/pdf-manager-1.0.jar app.jar
COPY --from=build /app/public ./public
RUN mkdir -p uploads outputs
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
