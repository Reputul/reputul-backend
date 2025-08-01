# -------- Stage 1: Build the application --------
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

# Copy the Maven wrapper files first to leverage Docker cache
COPY .mvn/ .mvn
COPY mvnw .
COPY pom.xml .

# Pre-download dependencies to speed up builds
RUN chmod +x mvnw && ./mvnw dependency:go-offline

# Copy the rest of the source code
COPY src ./src

# Build the application and skip tests
RUN ./mvnw clean package -DskipTests

# -------- Stage 2: Run the application --------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=build /app/target/reputul-backend-0.0.1-SNAPSHOT.jar app.jar

# Expose the port your Spring Boot app runs on
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
