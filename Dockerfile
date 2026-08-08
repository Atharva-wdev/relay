# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom first for dependency caching
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# Copy source and build
COPY src ./src
COPY scripts ./scripts
RUN mvn -q -DskipTests clean package

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# App port
EXPOSE 8081

# Copy jar from build stage (adjust if artifact name differs)
COPY --from=build /app/target/*.jar /app/app.jar

# Sensible defaults (override at runtime if needed)
ENV SERVER_PORT=8081
ENTRYPOINT ["java","-jar","/app/app.jar"]