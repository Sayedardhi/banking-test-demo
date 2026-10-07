# syntax=docker/dockerfile:1
# Builds one Spring Boot ledger service from this checkout for source-based E2E runs.
# Build context: repository root. Usage: --build-arg SERVICE=ledgerwriter
FROM maven:3.9-eclipse-temurin-17 AS build
ARG SERVICE
# Optional Maven Central mirror, e.g. when repo.maven.apache.org rate-limits (HTTP 429).
ARG MAVEN_MIRROR_URL=
WORKDIR /workspace
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then mkdir -p /usr/share/maven/ref && printf '%s' \
      "<settings><mirrors><mirror><id>e2e-mirror</id><mirrorOf>central</mirrorOf><url>$MAVEN_MIRROR_URL</url></mirror></mirrors></settings>" \
      > /usr/share/maven/conf/settings.xml; fi
COPY pom.xml ./
COPY src/ledgermonolith/pom.xml src/ledgermonolith/pom.xml
COPY src/ledger src/ledger
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl src/ledger/${SERVICE} -am package \
      -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true \
 && cp src/ledger/${SERVICE}/target/${SERVICE}-*.jar /app.jar

# Same base image the upstream Jib build uses.
FROM eclipse-temurin:17.0.4.1_1-jre-alpine@sha256:e1506ba20f0cb2af6f23e24c7f8855b417f0b085708acd9b85344a884ba77767
# Optional Start-Class override, used when a module's manifest names a class that does not exist.
ARG MAIN_CLASS=
ENV LOADER_MAIN=${MAIN_CLASS}
COPY --from=build /app.jar /app/app.jar
ENTRYPOINT ["sh", "-c", "if [ -n \"$LOADER_MAIN\" ]; then exec java -cp /app/app.jar -Dloader.main=\"$LOADER_MAIN\" org.springframework.boot.loader.launch.PropertiesLauncher; else exec java -jar /app/app.jar; fi"]
