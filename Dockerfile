FROM node:22-bookworm-slim AS ui
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY --from=ui /build/frontend/dist ./frontend/dist
RUN mvn -B -Dexec.skip=true -DskipTests package
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system platform && useradd --system --gid platform platform && mkdir -p /app/data && chown -R platform:platform /app
WORKDIR /app
COPY --from=build /build/target/api-intelligence-platform-1.0.0-SNAPSHOT.jar app.jar
USER platform
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
VOLUME /app/data
ENTRYPOINT ["java","-jar","app.jar"]
