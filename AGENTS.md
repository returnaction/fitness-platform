# Instructions for the New Java Project

This root file applies to the entire repository, including the
[System Design documentation](system-design/README.md). More specific
instructions in nested directories supplement the root instructions.

## SDD Scope and Workflow

Completed specifications remain in `specs/` as historical requirements and
verification records; they do not authorize new work.

### Active specification

- **Specification:** `specs/007-exercise-catalog-readonly-mvp/`.
- **Branch:** `52-exercise-catalog-service`, created from the current `main`.
- **Status:** `in-progress` — activated by the user 2026-11-17.
- **Goal and acceptance criteria:** [spec.md](specs/007-exercise-catalog-readonly-mvp/spec.md).
- **Included scope:** separate read-only Exercise Catalog database and service,
  Liquibase schema and approved seed data, read-only REST through Gateway, and
  Challenge-to-Catalog gRPC validation as defined in `spec.md`.
- **Excluded scope:** admin API and administration tables, Kafka/outbox, manual
  data edits, and changes to Challenge, Video Proof or Moderation beyond the
  agreed integration.
- **Plan and tasks:** [plan.md](specs/007-exercise-catalog-readonly-mvp/plan.md)
  and [tasks.md](specs/007-exercise-catalog-readonly-mvp/tasks.md).
- **Verification:** record required checks and evidence in `verification.md` if
  created for this specification; tasks currently define the required checks.
- **Authorized work:** the user authorized selecting this specification and
  recording it in this file. No implementation task is authorized yet.
- **Execution mode:** after each authorized task, report evidence and stop for
  review unless the user explicitly authorizes continued work.
- **Questions and decisions:** record material questions and decisions in
  `questions.md`, with affected tasks, evidence, options, recommendation and
  any blocked scope. The open decisions currently listed in `spec.md` and
  `tasks.md` remain unresolved.

Replace these placeholders only with information established by the user or
verified in the repository. Do not create a specification or branch merely to
fill in the template. Once the selected specification is complete, clear its
active scope here and keep its history in its own directory.

### Before each authorized development step

1. Read `spec.md`, `plan.md` and `tasks.md` in the active specification directory.
   Identify the requested task, its acceptance criteria and dependencies.
   Respect the user's request and the current branch; do not assume the first
   unchecked item is always actionable.
2. Tell the user which task is being addressed and why. Inspect the relevant
   code, tests, contracts, configuration and migrations before editing.
3. Consult `system-design` for relevant context or referenced decisions. It is
   not an independent implementation queue. Explain contradictions and ask
   the user when a material product decision is needed.
4. Implement and verify only the authorized task and its necessary dependencies.
   Record meaningful newly discovered gaps in the task list; do not silently
   treat them as completed or launch unrelated work.
5. Check a task only when its whole agreed scope has been implemented and
   verified. Keep partial or unverified work open; record skipped checks and
   their implications. Keep linked PR statuses accurate when verifiable.
6. Report changes, checks, limitations and remaining work. Follow the agreed
   execution mode. When the specification is complete or all remaining tasks
   are blocked, report the status and ask what to do next rather than choosing
   another feature.

Commits, push, merge, deploy and user-database migrations require explicit
user authorization. Authorization from a previous specification does not
carry over. Preserve existing user changes and established security guarantees;
label assumptions and unverified protection as such.

## Project and Technology

The fitness application is being designed as a set of microservices. Use
Eclipse Temurin JDK 25.0.5 LTS and Spring Boot 4.1.2. Maven Wrapper is the
standard build entry point. Pin dependency and verification-tool versions in
the project configuration; do not invent an existing implementation or
versions. Verify library compatibility.

Architectural directions: PostgreSQL, with data owned by the service that uses
it; Kafka for events; RabbitMQ for agreed background jobs; HTTP and gRPC where
appropriate; and S3-compatible storage for video. Use Liquibase for database
schema migrations. In local development PostgreSQL is installed and managed
outside Docker; create one explicitly named database per service and do not add
PostgreSQL containers to Docker Compose. Kafka for local development runs in
Docker Compose in KRaft mode, without ZooKeeper. Refer to the documentation for
concrete contracts and constraints. Running several components locally on one
server does not make separate servers fault tolerant.

## How to Work

- Before changing deployment files or preparing a release, read
  [the deployment guide](deployment/README.md). Follow its existing-service
  and new-service checklists, and update it when the deployment mechanism changes.
  This guide does not authorize commit, push, merge, deploy or database migrations.
- Follow the shared [engineering standards](docs/engineering-standards.md),
  including the Clean Code rules, when writing or changing service code.
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

## Git Branches

- Start every implementation or documentation change in a new branch created
  from the current `main`. Do not make ordinary changes directly on `main`.
- Name every work branch with the next unused positive integer followed by a
  short kebab-case description: `<number>-<description>`. For example:
  `1-branch-governance`, `2-identity-service`, and `3-ci-pipeline`.
- Branch numbers form one strictly increasing sequence for the whole
  repository. Never reuse a number, including after a branch has been merged
  or deleted. The next branch number is exactly one greater than the highest
  number already used: after `2-...`, the next branch must be `3-...`; gaps and
  arbitrary numbers are not allowed. Determine it from both local and remote
  branches.
- Keep one coherent task in one branch. Merge it through a pull request after
  its required checks pass; do not push directly to `main`.

## Documentation

For architecture context outside the active SDD workflow, start with
[the System Design README](system-design/README.md) and the
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
