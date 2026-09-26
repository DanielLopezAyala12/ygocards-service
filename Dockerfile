# Two stages, because factor V asks for build and run to be separate things rather than separate
# phases of the same thing. The build stage holds a JDK, a Maven Wrapper and the whole source
# tree. The runtime stage holds a JRE and one jar. Nothing that was needed to produce the
# artefact can influence the process that runs it, because none of it is in the final image.

# --- Build --------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.8_9-jdk-noble AS build

WORKDIR /build

# The wrapper and the manifest first, on their own layer. Dependencies change far less often
# than source does, so resolving them before the code is copied means an ordinary code change
# does not re-download the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

# --- Runtime ------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.8_9-jre-noble AS runtime

# A process that never writes to disk has no reason to be able to. Running as root inside a
# container is a habit rather than a requirement, and this application does not need it.
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin ygocards

WORKDIR /app
COPY --from=build --chown=ygocards:ygocards /build/target/ygocards-service.jar app.jar

USER ygocards

# Documentation rather than configuration. The port the process actually binds comes from the
# environment, so this value matches the default and nothing more.
EXPOSE 8080

# Exec form on purpose: it makes the JVM process 1, so a SIGTERM from "docker stop" reaches it
# directly. Through a shell the signal would go to the shell, the JVM would never run its
# shutdown hooks, and the graceful drain configured in application.properties would be silently
# lost. This is the container equivalent of the problem documented for Windows in the stop
# scripts, and it is the reason the form of this line matters.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
