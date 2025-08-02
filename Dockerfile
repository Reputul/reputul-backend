# Single-stage build with JDK
FROM eclipse-temurin:21-jdk

# Install bash (needed for Maven wrapper)
RUN apt-get update && apt-get install -y bash && rm -rf /var/lib/apt/lists/*

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