# Single-stage build with JDK (simpler and more reliable)
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copy everything and build
COPY . .

# Make wrapper executable and build
RUN chmod +x mvnw && ./mvnw clean package -DskipTests

# Verify the JAR was created and show its name
RUN ls -la target/

# Expose port
EXPOSE 8080

# Use shell form to handle wildcard expansion
CMD java -jar target/*.jar