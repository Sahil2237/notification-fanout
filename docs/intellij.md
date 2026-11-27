# IntelliJ with Rancher Desktop

**27 November network update:** [Provider LB-only backend ingress and restricted egress are deployed; SSH sources remain unchanged by user decision](network-hardening.md). Historical observations below remain version-pinned.

**Start here:** [Current onboarding, public/private addresses, REST smoke test and per-Droplet metrics](onboarding.md).

The requested setup is IntelliJ as the editor/test interface, with Java/Maven/PostgreSQL running in Rancher-managed containers. Source remains /workspaces/notification-fanout, persisted in notification-workspaces. Do not open a host copy in the PostgreSQL repository.

Installed host IntelliJ Ultimate 2024.1.4 predates Java 25 language support. A verified IntelliJ 2025.3 backend has been downloaded, checksum-verified and started under /workspaces/.tools/idea without replacing the host IDE. JetBrains Gateway can download a matching client for that backend. The matching client is connected, the user completed activation, and the saved Maven Verify configuration was run through IntelliJ. Its UI showed BUILD SUCCESS, with 7 unit and 17 integration tests passing inside Rancher on Java 25. No trial or license terms were accepted by the agent.

For a new setup with a modern IntelliJ: configure Docker connection to the Rancher Desktop socket under your home directory (.rd/docker.sock; confirm its location on the interview workstation), then use Dev Containers with the checked-in configuration. The IDE backend, project SDK and Maven execution must be inside Ubuntu. Java home: /opt/java/openjdk. CLI Maven home: /usr/share/maven; the verified IntelliJ run used bundled Maven 3.9.11. Existing prepared container needs no nested Docker socket.

After opening the remote project, import pom.xml, select JDK 25 and run the checked-in **Verify — unit and integration tests** Maven configuration. This invokes mvn verify, including real PostgreSQL integration tests. Running only a JUnit gutter test does not run migrations/complete integration validation. config/test.yml points to the isolated notifications_test database; test receiver uses 19090 while demo uses 9090. Tests truncate their test tables and must never target real customer data.

The shell fallback inside IntelliJ terminal is ./scripts/verify.sh. Start app with ./scripts/start-local.sh, which copies the packaged JAR so a later Maven build cannot overwrite the running executable. Unit tests are mvn test. Database migration is java -jar target/notification-fanout.jar db migrate config/local.yml. Default local configuration is for private development only.

The assessment brief lists VS Code/Cursor; confirm interviewer acceptance of IntelliJ. This does not change container or workspace requirements.
