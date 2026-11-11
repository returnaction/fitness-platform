# Fitness Platform

The future fitness platform consists of a Flutter mobile application and Java /
Spring Boot services. Its architecture and agreed product rules are documented
in [System Design](system-design/README.md).

## Repository layout

- `apps/mobile/` — the future Flutter application.
- `services/` — independent Spring Boot services.
- `contracts/` — cross-service Protobuf and event contracts.
- `docs/` — engineering standards and development guidance.
- `platform/` — local infrastructure configuration, such as Docker Compose.
- `system-design/` — architecture, domain model, service contracts, and ADRs.

The repository contains the initial bootstrap of Identity Service. Business
functionality is added only after its agreed System Design contracts. Coding
conventions for all future service modules are in
[Engineering Standards](docs/engineering-standards.md).

## Build baseline

- JDK: Eclipse Temurin 25.0.5 LTS.
- Framework baseline: Spring Boot 4.1.2.
- Build tool: Maven Wrapper.

Run the build from Windows with:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Run the documentation link check with:

```powershell
.\scripts\verify-markdown-links.ps1
```

## Local Identity configuration

Create the ignored local configuration file once:

```powershell
.\scripts\initialize-identity-local-env.ps1
```

Then set the real `DATABASE_PASSWORD` in
`services/identity-service/.env`. The local Spring profile imports that file
automatically; it is never committed or copied into a Docker image.

Before the first run, a PostgreSQL administrator must create the service role
and the `fitness_identity` database. The exact local and server setup
steps, minimal rights, and verification commands are in
[PostgreSQL setup](docs/database-setup.md).

For local JWT signing key setup, see [local JWT keys](docs/local-jwt-keys.md).
