# Builds one ledger Spring Boot service from the module directory (the build context).
# Used only by tests/e2e/compose.source.yaml; production images are built with Jib.
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MAVEN_MIRROR_URL=
WORKDIR /src
COPY . .
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>central-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q package -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true \
    && cp "$(ls target/*.jar | grep -v -- '-plain.jar$' | head -n1)" /app.jar \
    && f=$(grep -rl '@SpringBootApplication' src/main/java | head -n1) \
    && echo "$f" | sed -e 's|^src/main/java/||' -e 's|\.java$||' -e 's|/|.|g' > /main-class

FROM eclipse-temurin:17-jre
COPY --from=build /app.jar /app/app.jar
COPY --from=build /main-class /app/main-class
EXPOSE 8080
# Launch the @SpringBootApplication class found in source rather than the manifest Start-Class:
# transactionhistory's spring-boot-maven-plugin <mainClass> names a class that does not exist
# (TransActionHistoryApplication), which only matters for `java -jar`; Jib images infer the class.
ENTRYPOINT ["sh", "-c", "exec java -Dloader.main=$(cat /app/main-class) -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher"]
