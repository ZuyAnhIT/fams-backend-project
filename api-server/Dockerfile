# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
# Always start from an empty target directory. Without `clean`, deleted Java
# classes can survive in a cached Docker layer and still be packaged into the
# runnable jar (for example the legacy Department entity removed by V71).
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system --gid 1001 fams && \
    useradd --system --uid 1001 --gid 1001 --no-create-home fams
COPY --from=builder --chown=fams:fams /build/target/*.jar app.jar
USER fams
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
