# -------- Stage 1: Build the application --------
FROM eclipse-temurin:21-jdk AS build


WORKDIR /app

# Copy wrapper and config first (cache efficiency)
COPY .mvn/ .mvn
COPY mvnw .
COPY pom.xml .

# Make wrapper executable and go offline
RUN chmod +x mvnw && ./mvnw dependency:go-offline

# Copy source code and build
COPY src ./src
RUN ./mvnw clean package -DskipTests

# -------- Stage 2: Run the application --------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the JAR from the build stage (now confirmed name and path)
COPY --from=build /app/target/reputul-backend-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
