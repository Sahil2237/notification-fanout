ARG JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c
FROM ${JAVA_IMAGE} AS development
RUN sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g; s|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update && apt-get install -y --no-install-recommends maven git curl jq python3 postgresql-client ripgrep gh \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspaces/notification-fanout
CMD ["sleep", "infinity"]

FROM development AS build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM ${JAVA_IMAGE} AS runtime
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app
WORKDIR /opt/app
COPY --from=build /workspaces/notification-fanout/target/notification-fanout.jar ./notification-fanout.jar
COPY config/local.yml ./config.yml
COPY config/production.yml ./production.yml
COPY config/droplet.yml ./droplet.yml
USER app
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/opt/app/notification-fanout.jar"]
CMD ["server", "/opt/app/config.yml"]
