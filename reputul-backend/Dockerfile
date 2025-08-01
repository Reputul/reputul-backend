# -------- Stage 1: Build the application --------
FROM eclipse-temurin:22-jdk AS build

WORKDIR /app

# Copy the Maven wrapper and project files
COPY . .

# Build the application and skip tests
RUN ./mvnw clean package -DskipTests

# -------- Stage 2: Run the application --------
FROM eclipse-temurin:22-jre

WORKDIR /app

# Copy the JAR from the build stage
COPY --from=build /app/target/reputul-backend-0.0.1-SNAPSHOT.jar app.jar

# Expose the port Spring Boot runs on
EXPOSE 8080

# Run the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
