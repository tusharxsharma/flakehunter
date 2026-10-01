# syntax=docker/dockerfile:1

# ---------- Build stage: compile and package with Maven ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only the build definition first: this layer (all dependencies) stays cached
# until pom.xml changes, so code-only changes rebuild in seconds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/api-service-*.jar extract --layers --launcher --destination target/extracted

# ---------- Runtime stage: JRE only, non-root, layered ----------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 1001 app
WORKDIR /app

# Spring Boot layers, least to most frequently changing, for maximum Docker cache reuse.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
