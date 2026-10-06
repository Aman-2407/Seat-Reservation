# ---- stage 1: build the jar ----
# Using the Maven wrapper from the repo so the build doesn't depend on a Maven install.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy only the build files first. Docker caches this layer, so dependencies are not
# re-downloaded every time I change a line of code.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src ./src
RUN ./mvnw -q -B -DskipTests package

# ---- stage 2: small runtime image ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# don't run as root inside the container
RUN useradd --system --create-home appuser
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container's memory limit,
# which matters on small free-tier hosts.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]