# Instructions for the New Java Project

This root file applies to the entire repository, including the
[System Design documentation](system-design/README.md). More specific
instructions in nested directories supplement the root instructions.

## Active SDD Scope: 002-identity-structure

Until the user explicitly changes the scope, a request to continue development
means continuing [002-identity-structure](specs/002-identity-structure/spec.md).
Do not start implementing another feature or a system-design roadmap item just
because it exists in the documentation. When the specification is complete or
all remaining tasks are blocked, report the status and ask the user what to do
next rather than choosing a different feature.

Before each development step:

1. Read the current [task list](specs/002-identity-structure/tasks.md),
   [specification](specs/002-identity-structure/spec.md), and
   [plan](specs/002-identity-structure/plan.md). Identify an open task, its
   acceptance criteria, and any dependencies. Respect an explicit user request
   and the current branch; do not assume the first unchecked item is always
   actionable.
2. Tell the user which task is being addressed and why. Inspect the relevant
   code, tests, contracts, configuration, and migrations before editing.
3. Consult `system-design` when it provides context or a referenced decision
   for that task. It is not an independent queue of implementation tasks.
   Do not expand scope based on it. Explain contradictions, propose a
   resolution, and ask the user when a material product decision is needed.
4. Implement and verify the selected task and its necessary dependencies.
   Record meaningful newly discovered gaps in the task list; do not silently
   treat them as completed or launch unrelated work.
5. Update the task list using evidence: check an item only when its whole scope
   has been implemented and verified. Keep partial, skipped, or unverified work
   open, splitting tasks where useful. Keep linked PR statuses accurate when
   their state can be verified.
6. Report the changes, checks and their limitations, remaining work, and the
   next task tied to the specification. Continue with another in-scope task
   when the user has authorized continued work and no intervention is needed.

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
