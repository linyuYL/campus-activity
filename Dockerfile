FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /project
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=ui /ui/dist ./src/main/resources/static
RUN mvn -B package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /project/target/campus-activity-1.0.0.jar app.jar
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java","-Duser.timezone=UTC","-jar","app.jar"]
