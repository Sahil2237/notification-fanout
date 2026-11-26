# Primary references

**November 26 redesign:** [Current asynchronous matching, 10-minute caches, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

- Dropwizard 5 requirements: https://www.dropwizard.io/en/stable/getting-started.html
- Exact Dropwizard dependency alignment: https://github.com/dropwizard/dropwizard/blob/v5.0.2/dropwizard-dependencies/pom.xml
- JDBC versions: https://jdbc.postgresql.org/download/
- PostgreSQL queue locking: https://www.postgresql.org/docs/current/sql-select.html
- SSRF and connection-time destination checks: https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- IntelliJ Java 25: https://www.jetbrains.com/idea/whatsnew/2025-3/
- IntelliJ remote backend connections: https://www.jetbrains.com/help/idea/remote-development-a.html

Dependency compatibility was additionally checked by compiling and running the integration suite on the selected Java 25 distribution. Vendor minimum-version support alone is not treated as proof.


## Load-balancer verification, 25 November 2026

Live settings were read from the DigitalOcean Control Panel and recorded in [sanitized evidence](evidence/load-balancer-2026-11-25.json). Provider behavior is supported separately by [Load Balancers Features](https://docs.digitalocean.com/products/networking/load-balancers/details/features/) and the [Load Balancers API reference](https://docs.digitalocean.com/reference/api/reference/load-balancers/): distribution is provider-managed and the former algorithm selector is deprecated. A deprecated example field is not evidence that the active scheduler is traditional round-robin. Health timing, affinity and other effective values come from the live settings inspection, not assumed defaults.
