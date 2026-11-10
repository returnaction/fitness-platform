# Domain Model

## User

User registers, logs in, creates challenges, joins challenges, submits videos, reviews other users, earns coins.

Identity owns the following tables:

- `users` stores the user identifier, normalized email, and current access status.
- `user_credentials` stores the password hash separately from the user record.
- `roles` stores database-backed security roles.
- `user_roles` assigns one or more roles to a user.
- `auth_sessions` represents independent logins on user devices.
- `refresh_tokens` stores hashed, single-use tokens belonging to a session.

User statuses:

- `PENDING`
- `ACTIVE`
- `SUSPENDED`
- `DISABLED`

Status values are constrained by both application types and a database check
constraint. Status changes must happen through explicit use cases rather than
generic profile updates.

Login is allowed only for `ACTIVE` users with at least one active role.
`SUSPENDED` and `DISABLED` users are denied after their password is verified.
Soft-deleted users are treated as unknown so the API does not disclose that an
email belongs to a deleted account.

Each login creates a separate `AuthSession` with a fixed 30-day expiration.
Logout marks `AuthSession.revoked_at` and records an immutable first
`revoke_reason`; refresh-token rows are retained for rotation/reuse history.
Current-session logout revokes one session, while logout-all revokes every
active session owned by the user.
An auth session owns a chain of opaque, single-use `RefreshToken` records.
The raw 256-bit secret is returned to the client once; only its SHA-256 verifier
is persisted. Rotation marks the old token as used and links it to its
replacement. Confirmed reuse revokes only the compromised session.

## Roles

Security roles:

- USER
- MODERATOR
- ADMIN
- SUPER_ADMIN

The four system roles are seeded by the Identity migration. `users` and `roles`
have a many-to-many relationship through `user_roles`. Public registration
always assigns `USER`; clients cannot select their own security role.

`users.id` uses UUID because users are main business entities. The small,
system-managed role catalog uses deterministic `SMALLINT` identifiers (`1`–`4`),
while the unique role `code` remains the stable business identifier. Application
logic must use role codes rather than depend on numeric IDs.

Only active role codes are embedded in a 15-minute access token. Role changes
therefore take effect no later than the expiration of the current access token.

Protected requests use a hybrid current-identity model. PostgreSQL is the source
of truth for user existence, email, soft deletion, and account status. Verified
JWT claims are the source of roles for the lifetime of the access token. An
authenticated identity contains `id`, current `email`, current `status`, and
typed token `roles`; it is not a user profile.

Account plans:

- FREE
- PREMIUM
- COACH
- ORGANIZATION

Premium is not a role. Premium is a subscription/account plan.

## Accounts profile

Accounts owns `user_profiles`. Each `users` row has exactly one profile through
the shared `user_id` primary key and foreign key. The profile stores optional
`nickname`, `first_name`, `last_name`, `date_of_birth`, and `bio`, plus required
`timezone` and `visibility`.

`nickname` is optional until a future product flow requires it. Once supplied,
it is unique without regard to case. `timezone` uses an IANA name such as
`Europe/Moscow` and defaults to `UTC`. `visibility` is `PRIVATE` by default and
will govern a future public-profile API; this branch does not expose any other
user's profile. The full date of birth is private and is never intended for a
future public profile response.

## Exercises

Exercise Catalog owns a database-driven reference catalog. It consists of
`catalog_categories`, `catalog_exercises`, and `catalog_variations`:

```text
ExerciseCategory → ExerciseDefinition → ExerciseVariant
```

All catalog entity identifiers are `SMALLINT` values from `1` to `32767`.
REST and gRPC use those numeric IDs; the stable English `code` remains a
business identifier, but is not a substitute for the ID in a relationship. An
exercise has one primary metric: `REPETITIONS` or `DURATION`. A variation
inherits that metric and defines its target range and step, which Challenge
must validate before publication. The authoritative table and API contract is
[Exercise Catalog v1](../services/exercise-catalog/57-exercise-catalog-service.md).

The initial system catalog contains `SQUAT`, `PUSH_UP`, `BURPEE`,
`JUMPING_JACK`, `JUMP_ROPE`, and `PLANK`. Push-ups and burpees have several
variants; every active definition has a `STANDARD` variant.

Catalog records are never physically deleted. `is_active` hides a category,
definition, or variant from public selection while retaining it for future
challenge history. A future Challenge module must snapshot the selected
variant's user-facing text; later catalog edits must not rewrite old challenges.

## Challenges

Challenges owns `challenges`, `challenge_workout_rules`, `challenge_days`, and
`challenge_workouts`. In the first narrow slice, a user creates a
private-to-the-author `DRAFT` and one workout rule.
`Challenge.creator_id` identifies the author. Publishing a draft creates the
author's first `ChallengeParticipant` with status `JOINED`. An OPEN challenge
has one `CoinHold` for that participant: the amount is moved from the wallet's
available balance to its locked balance and is represented by an immutable
`CHALLENGE_STAKE_LOCK` ledger entry. Cancelling an unfilled OPEN challenge
releases that same hold through `CHALLENGE_STAKE_RELEASE`.

Each rule stores external Catalog IDs and a snapshot of the exercise/variation
codes, names, and primary metric; it does not have a cross-database FK to a
live Catalog row. This keeps the draft understandable if Catalog text changes
later. Catalog remains the source of active-state and target-range validation.

Clients explicitly choose `HEAD_TO_HEAD` or `GROUP`. H2H is the specialized
`WHEN_FULL` case with exactly two players. GROUP accepts 2..20 players and uses
either `WHEN_FULL` or `FIXED_DATE`; the latter stores min/max and an immutable
scheduled UTC instant.

Challenge modes:

- SOLO
- HEAD_TO_HEAD
- GROUP
- ONE_VS_MANY
- TEAM

Challenge statuses:

- DRAFT
- OPEN
- MATCHED
- ACTIVE
- AWAITING_REVIEW
- FINISHED
- CANCELLED
- ARCHIVED

For `WHEN_FULL`, the last accepted join closes the roster (`MATCHED`) and a
worker assigns the actual UTC `starts_at`. For `FIXED_DATE`, becoming full only
closes the roster; activation still waits for `scheduled_starts_at`. At that
instant an insufficient roster is cancelled and every active hold is returned.
A background lifecycle worker moves eligible challenges to `ACTIVE`, and their
participants from `JOINED` to
`ACTIVE`, inside one PostgreSQL transaction. At that same point it materializes
one 24-hour `ChallengeDay` per `duration_days` and `workouts_per_day`
`ChallengeWorkout` rows. Each workout owns a full immutable snapshot of its
exercise and load rule, so later catalog or draft changes cannot alter an
active challenge. `Challenge.schedule_timezone` is the author's timezone
snapshot from publication; it supports future player-facing timezone rules
without mutating an existing schedule. No coin balance changes at start: both
stakes were already locked.

## Coins

Coins are virtual only.
Coins cannot be bought.
Coins cannot be withdrawn.
Coins cannot be exchanged for money or prizes.

Coins owns two foundation tables: `coin_wallets` and `coin_ledger_entries`.
Every user has exactly one wallet through the shared `user_id` UUID primary key
and foreign key. Registration creates the empty wallet in the same transaction
as the Identity user and Accounts profile; the migration backfills zero wallets
for existing users.

`coin_wallets` is a fast current projection with non-negative `available_balance`,
`locked_balance`, `lifetime_earned`, and `lifetime_spent`, all initialised to zero.
It is not a source of untracked balance changes.

`coin_ledger_entries` is the immutable history of future coin changes. Each row
has a UUID, a positive `amount`, an allowed transaction type, both post-operation
balance snapshots, and an optional all-or-nothing business reference pair
(`reference_type`, `reference_id`). PostgreSQL rejects updates and deletes of
ledger entries. Corrections must append a `SYSTEM_REVERSAL` entry instead.

The allowed types are `DAILY_LOGIN_REWARD`, `CHALLENGE_STAKE_LOCK`,
`CHALLENGE_STAKE_RELEASE`, `CHALLENGE_WIN_REWARD`, `CHALLENGE_LOSE_PENALTY`,
`ADMIN_ADJUSTMENT`, and `SYSTEM_REVERSAL`. Future mutation scenarios will lock
the wallet row, update the projection, and append the corresponding ledger entry
inside one explicit transaction.

`daily_reward_claims` stores the immutable business fact behind a
`DAILY_LOGIN_REWARD`: UUID, user, local `claim_date`, UTC `claimed_at`, historical
`claimed_amount`, and its one-to-one ledger entry. `(user_id, claim_date)` is
unique. The date is calculated in the user's current IANA profile timezone;
additionally, at least 24 hours must pass after any prior daily claim, preventing
an extra reward after changing timezone. Repeated mobile requests return the
existing claim rather than create a second ledger entry.

## Submission metadata foundation

`challenge_submissions` records that one challenge participant started proof
submission for one materialized `ChallengeWorkout`. It stores the owning
`challenge_id`, `participant_id`, `challenge_workout_id`, `status`, and audit
timestamps. `(participant_id, challenge_workout_id)` is unique, so one player
cannot create competing submissions for the same workout.

The only current status is `PENDING_UPLOAD`. It intentionally does not mean that
a video exists. The future Media module will own the object storage upload and
will introduce `UPLOADED` and `PENDING_REVIEW` only after storage confirmation.
`submission_idempotency_records` belongs to Submissions, not Challenges; it
maps a user, operation, idempotency key, request fingerprint, and submission for
safe mobile retry.

## MediaAsset foundation

`media_assets` is a one-to-one metadata record for a `ChallengeSubmission`.
It contains the private bucket, server-generated key, owner, storage provider,
declared/confirmed content type, confirmed size and timestamps. Its technical
states are `PENDING_UPLOAD` and `READY`; after HeadObject confirms a maximum
100 MB object, its submission becomes `PENDING_REVIEW`. There is no public URL
or peer download API in this phase.

For an admitted TVP playback, `MediaAsset` additionally pins the immutable
playback ID, S3 object version and SHA-256. Legacy records retain null values
for those fields. A separate immutable admission decision ties a submission to
its attempt, integrity assessment and playback; it records identity, liveness,
scene and exercise checks as `NOT_PERFORMED` rather than as successful checks.
Admission makes the submission `PENDING_REVIEW`; only a human review can move
it to `APPROVED` or `REJECTED`.

## Moderator/admin review

`submission_reviews` stores exactly one immutable human decision per
`ChallengeSubmission`: reviewer, MediaAsset and its confirmed `media_version`,
`APPROVE` or `REJECT`, optional rejection reason and timestamp. A database unique
constraint on `submission_id` is the final protection against two moderators
creating competing decisions. `REJECT` requires a reason; `APPROVE` cannot have
one. The final submission states are `APPROVED` and `REJECTED`.

## Challenge outcome and virtual settlement

`challenge_results` stores one immutable header per finished challenge.
`challenge_result_participants` stores the immutable per-player placement,
approved progress, terminal reason, stake return, forfeited share, remainder
bonus, and total received. GROUP outcomes are `SOLE_WINNER`, `TOP_FINISHERS`,
or `ALL_COMPLETED`; legacy H2H retains `WINNER`, `DRAW`, and `NO_WINNER`.
Timely `PENDING_REVIEW` moves the challenge to `AWAITING_REVIEW` and cannot be
auto-decided. A single surviving player wins immediately. If the last cohort
fails at the same greatest progress, that cohort gets its own stakes back and
splits only stakes forfeited earlier. Competition placement is 1,2,2,4.

The mobile lifecycle projection is derived from the current `Challenge` and
`ChallengeParticipant`; it is not stored as a second source of truth. It exposes
server-authoritative `can_leave`/`can_cancel` permissions and maps the current
state to the appropriate draft, upcoming, active, failed, review-waiting,
result, or cancelled presentation.

Settlement reuses active `coin_holds` and immutable ledger entries. Holds end
as `RETURNED` or `FORFEITED` with a terminal reason. A draw or
no-winner outcome releases each original stake. A winner receives their released
stake plus `CHALLENGE_WIN_REWARD` for the loser's hold; the loser receives
`CHALLENGE_LOSE_PENALTY`. Challenge, participants, holds, wallets, ledger and
result change in one PostgreSQL transaction. GROUP uses integer division; one
remainder coin at a time goes first to the creator when eligible, then by
`joined_at` and participant UUID. Reprocessing observes the unique result and
terminal holds, so it cannot credit twice.

## Trusted Video Proof

The Phase 10 contract defines, and Phase 12 now persists, the following
Verification-owned aggregate:

```text
ChallengeSubmission
  -> CaptureAttempt
       -> CaptureFragment
       -> CaptureCommitment / ServerReceipt
       -> CapturePrompt / CaptureEvent
       -> CaptureManifest
       -> VerificationAssessment (Phase 14 PR 2: technical integrity only)
       -> technical admission / immutable moderation decision (Phase 14)
```

`ChallengeSubmission` remains the single business proof slot for one participant
and materialized workout. It may have unlimited sequential attempts before the
server deadline, but a partial unique invariant permits only one active attempt.
After a successful seal, another attempt requires authoritative `RECORD_AGAIN` or
`TECHNICAL_FAILURE`; a player cannot choose among several sealed candidates.

Attempt terminal outcomes are `ACCEPTED_FOR_EXERCISE_REVIEW`, `REVIEW_REQUIRED`,
`RECORD_AGAIN`, `REJECTED`, `TECHNICAL_FAILURE`, `EXPIRED`, and `ABORTED`.
Terminal attempt state is immutable. Retryable worker execution is separate, and
manual correction appends a superseding assessment/decision.

The Trusted Video Proof document map is maintained outside this repository; its
current phase plans and future work are therefore not linked here.

Proof integrity and exercise correctness are independent. Only
`ACCEPTED_FOR_EXERCISE_REVIEW` enters the existing moderator exercise decision.
`REVIEW_REQUIRED` blocks settlement. Confirmed tampering rejects the workout once;
future account sanctions belong to Phase 19.1. A player-side 24-hour upload-grace
expiry is a missed proof, not automatically a fraud accusation.

Policy, commitments, receipts, manifests, assessments, decisions and fraud
signals are immutable evidence. Historical media without that evidence is
`LEGACY_UNVERIFIED`, regardless of an old `MOBILE_CAMERA` source value.

Phase 12 creates the server-authoritative attempt before capture, persists the
policy, nonce binding, device and ephemeral keys, commitments/receipts, prompts,
manifest, upload object versions, incidents, extensions, idempotency results and
outbox events. A timely seal changes the submission to `PENDING_MEDIA_UPLOAD`;
complete confirmed media changes it to `PENDING_VERIFICATION`. Neither state is
a proof-authenticity verdict, and both block challenge settlement.
# Moderator review proof evidence

`SubmissionReviewProofEvidence` records the admitted attempt, playback, media
version and the workout and prompt context used for a TVP moderator decision.
It is immutable and is created atomically with `SubmissionReview`; a retry must
not create a second decision or evidence record. Older reviews and legacy media
do not receive invented evidence.
