# Instructions for the New Java Project

This file applies to the [system-design/](system-design/) directory. To apply
these rules to the entire future repository, place these instructions in that
repository's root `AGENTS.md` as well. More specific instructions in nested
directories supplement the root instructions.

## Project and Technology

The fitness application is being designed as a set of microservices. Use
Eclipse Temurin JDK 25.0.5 LTS and Spring Boot 4. Pin the exact dependency
versions, build tool, and verification tools in the project configuration once
they have been selected; do not invent an existing implementation or versions.
Verify library compatibility.

Architectural directions: PostgreSQL, with data owned by the service that uses
it; Kafka for events; RabbitMQ for agreed background jobs; HTTP and gRPC where
appropriate; and S3-compatible storage for video. Refer to the documentation
for concrete contracts and constraints. Running several components locally on
one server does not make separate servers fault tolerant.

## How to Work

- Act as an experienced backend developer: before making changes, inspect the
  task, related contracts, code, tests, migrations, and configuration, if they
  exist.
- Use patterns only when they fit the task and in proportion to it; do not add
  abstractions, brokers, or dependencies merely for the pattern itself. Explain
  material trade-offs.
- Respect domain boundaries: a service owns its data, and other services do not
  modify its tables directly. Controllers handle HTTP; business rules live in
  the domain and application use cases; infrastructure sits behind interfaces.
- Account for concurrent requests, transactions, security, backward
  compatibility, failures, and redelivery. Define idempotency for monetary
  operations and other repeatable commands. For reliable events, use the
  contract-defined outbox/inbox patterns and retries; do not promise
  exactly-once processing without proof.
- Do not keep a database transaction open during a network call. Distinguish
  transient failures from invalid messages, and do not retry permanent errors
  pointlessly.
- Never write passwords, tokens, or private data to logs. Validate input,
  check permissions, and do not trust the client in financial or video flows.
- Do not create commits or run merge, push, deploy, or migrations against a
  user database without explicit authorization. Preserve other people's
  changes.

## Documentation

Start with [the System Design README](system-design/README.md) and the
[readiness map](system-design/overview/37-service-contracts-worklist.md).
Requirements are in `system-design/requirements/`; DDD and the glossary are in
`system-design/domain/`; services are in `system-design/services/`;
interactions are in `system-design/interactions/`; infrastructure is in
`system-design/architecture/`; and ADRs are in `system-design/decisions/`.
Review current decisions and clearly label historical or unapproved material.

Maintain one authoritative description for each table with its owner; link to
it from other documents. For APIs and events, describe the owner,
authorizations, fields, examples, errors, versions, idempotency, transactions,
and failure handling. Update related documents when a contract changes.
Check `.drawio` diagrams structurally; claim visual verification only when it
has actually been performed.

## Verification and Communication

Test business rules and key errors, permissions, retries, and races. For
important database operations, use PostgreSQL integration tests; verify
migrations, contracts, and messaging with suitable integration tests. Run the
project's existing build, formatter, static analysis, and tests as relevant to
the task. When changing documentation only, check links and consistency; do
not automatically run a build without a reason. Do not describe unverified
work as working.

Explain things to the user in Russian and in plain language. Do not repeat
already made decisions as questions; propose solutions for technical details.
Confirm material changes to business rules. In the result, state what changed,
what was verified, and what limitations remain.
