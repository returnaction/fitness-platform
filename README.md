# Fitness Platform

The future fitness platform consists of a Flutter mobile application and Java /
Spring Boot services. Its architecture and agreed product rules are documented
in [System Design](system-design/README.md).

## Repository layout

- `apps/mobile/` — the future Flutter application.
- `services/` — independent Spring Boot services.
- `contracts/` — cross-service Protobuf and event contracts.
- `platform/` — local infrastructure configuration, such as Docker Compose.
- `system-design/` — architecture, domain model, service contracts, and ADRs.

The repository intentionally contains no generated application code yet. The
first implementation will follow the agreed System Design contracts.
