# Services

This directory will contain independent Spring Boot services. Each service will
have its own build file, tests, and owned data.
Each directory in this folder is an independent Spring Boot service and a Maven
module. Services do not share business entities, repositories, or database
tables. Their source-of-truth interaction schemas are in `../contracts/`.

Current modules:

- `identity-service/` — initial PENDING-registration API; full provisioning,
  activation and sessions are still in progress.
- `accounts-service/` — service bootstrap; profile tables and `EnsureProfile`
  will be added in the next increment.
