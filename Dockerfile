FROM maven:3.8.7-openjdk-8 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY public ./public
RUN mvn package -q

FROM openjdk:8-jre-slim
WORKDIR /app
COPY --from=build /app/target/pdf-manager-1.0.jar app.jar
COPY --from=build /app/public ./public
RUN mkdir -p uploads outputs
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
