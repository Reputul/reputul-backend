# Use the most basic, proven Ubuntu + OpenJDK approach
FROM ubuntu:22.04

# Install OpenJDK 21 and basic utilities
RUN apt-get update && \
    apt-get install -y openjdk-21-jdk wget && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Install Maven manually
RUN wget https://archive.apache.org/dist/maven/maven-3/3.9.4/binaries/apache-maven-3.9.4-bin.tar.gz && \
    tar xzf apache-maven-3.9.4-bin.tar.gz && \
    mv apache-maven-3.9.4 /opt/maven && \
    rm apache-maven-3.9.4-bin.tar.gz

ENV MAVEN_HOME=/opt/maven
ENV PATH=$MAVEN_HOME/bin:$PATH

WORKDIR /app

# Copy and build
COPY . .
RUN mvn clean package -DskipTests

# Verify build
RUN ls -la target/

EXPOSE 8080

# Run with explicit path
CMD ["/usr/lib/jvm/java-21-openjdk-amd64/bin/java", "-jar", "target/reputul-backend-0.0.1-SNAPSHOT.jar"]