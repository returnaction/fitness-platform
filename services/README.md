# Services

This directory will contain independent Spring Boot services. Each service will
have its own build file, tests, and owned data.
Each directory in this folder is an independent Spring Boot service and a Maven
module. Services do not share business entities, repositories, or database
tables. Their source-of-truth interaction schemas are in `../contracts/`.

Current modules:

- `identity-service/` — bootstrap only; user registration is not implemented
  yet.
