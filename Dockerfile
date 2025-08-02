# Use official Eclipse Temurin JDK for building
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and pom.xml first (for layer caching)
COPY .mvn/ .mvn
COPY mvnw .
COPY pom.xml .

# Make wrapper executable and download dependencies
RUN chmod +x mvnw && ./mvnw dependency:go-offline

# Copy source code and build
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Runtime stage with JRE only
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]