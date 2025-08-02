# Use official Maven image (includes JDK and Maven)
FROM maven:3.9.4-eclipse-temurin-21

WORKDIR /app

# Copy and build with Maven directly (not wrapper)
COPY . .
RUN mvn clean package -DskipTests

# List the JAR files to verify
RUN ls -la target/

# Expose port
EXPOSE 8080

# Run the JAR directly
CMD ["java", "-jar", "target/reputul-backend-0.0.1-SNAPSHOT.jar"]