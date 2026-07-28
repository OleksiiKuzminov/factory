# Operations Runbook — FOLIO AI SDLC Factory

Operational procedures for running the Factory in production. Pairs with
[backup-dr.md](backup-dr.md) (data durability) and [performance.md](performance.md)
(pool/engine sizing). Everything here is grounded in the current control plane —
statuses, config keys, and metrics named below are real.

> Scope note: console/REST authentication and webhook authentication both ship
> (see [Accounts and credentials](#accounts-and-credentials) — read it before
> following any step below, since every console page and `/api/**` call here
> needs credentials). Secrets *management* — vaulting the environment variables
> themselves, rotation, per-user accounts beyond the in-memory list — remains out
> of scope for this milestone.

## Accounts and credentials

Authentication is **on by default** (`FACTORY_AUTH_ENABLED=true`).

- **Accounts** — `FACTORY_AUTH_USERS` is a comma-separated list of
  `user:password` pairs, e.g. `qa-lead:<password>,rel-mgr:<password>`. A password that
  already carries an encoder id is stored as given, so a deployment should ship
  pre-encoded values rather than plaintext:
  `FACTORY_AUTH_USERS='qa-lead:{bcrypt}$2a$10$…,rel-mgr:{bcrypt}$2a$10$…'`.
  `,` is the entry separator, so a **plain** password must not contain one;
  pre-encode such a password instead (bcrypt output has no commas). Usernames are
  limited to `[A-Za-z0-9._@+-]`, and a malformed or duplicated entry fails startup
  rather than silently dropping an account that would then never authenticate.
  Confirm the resolved set on the startup log line
  `factory.auth.users resolved N account(s): [...]` (names only) — an account
  nobody declared means a password ate a separator.
- **No accounts configured** — one `admin` password is generated per boot and
  logged at WARN. Grep the startup log for:

  ```
  Using generated security password for user 'admin':
  ```

  It changes on every restart and attributes every review decision to that one
  account — configure `FACTORY_AUTH_USERS` for any shared deployment.
- **Console** — form login at `/login` (CSRF-protected session); HTTP Basic is
  accepted on the same pages, so `curl -u` reaches them without driving the form.
  An unauthenticated browser request is redirected to `/login`, not 401'd.
- **REST** — `/api/**` is HTTP Basic and stateless. `GET /api/**` additionally
  honours an existing console session, which is how the console's own JavaScript
  reads it.
- **Actuator** — `/actuator/health` and the liveness/readiness probes are public
  so orchestrator probes need no credentials; every other `/actuator/**` endpoint
  requires Basic. `FACTORY_AUTH_METRICS_PUBLIC=true` opens `/actuator/prometheus`
  and `/actuator/metrics` to an unauthenticated scraper.
- **Health detail is authorization-gated** — `show-details: when-authorized`, so
  the `db` and `connectors` components appear only for an authenticated caller.
  The aggregate status and both probes are unaffected. For connector state prefer
  `/status` (console) or `GET /api/status`.
- **Webhooks are exempt** from that chain — they carry their own credentials:
  `FACTORY_WEBHOOKS_SHARED_SECRET` checked against the `X-Factory-Webhook-Token`
  header (the legacy `?token=` query parameter still works, leaks into access and
  proxy logs, and logs a deprecation warning), plus GitHub's
  `X-Hub-Signature-256` HMAC over the raw body whenever
  `FACTORY_WEBHOOKS_GITHUB_SECRET` is set. **Unauthenticated while the shared
  secret is unset** — the app warns at startup.
- **`FACTORY_AUTH_ENABLED=false`** is only for a deployment already fronted by an
  authenticating proxy: every endpoint is permitted, the app logs a WARN at
  startup, and every HITL decision is attributed to `anonymous`. Health details
  are hidden in that mode too (nobody is authenticated) — use `/status`.

## Execution lifecycle & statuses

An execution row (`pipeline_execution.status`) moves through:

| Status | Meaning | Who advances it |
|---|---|---|
| `PENDING` | Runnable; waiting for a poller claim (or in retry backoff via `next_run_at`) | `ExecutionPoller` claims it → `RUNNING` |
| `RUNNING` | A worker thread is advancing a step | Engine, or the **lease reaper** if the worker died |
| `AWAITING_HITL` | Parked at a human gate | A reviewer decision in `/reviews` |
| `AWAITING_SUBFLOW` | Parent waiting on a child flow | Child completion / escalation reconciliation |
| `COMPLETED` | Finished successfully (terminal) | — |
| `FAILED_ESCALATED` | Retry budget exhausted or engine error; **has a pending escalation review** (terminal-but-resumable) | Approving/rejecting the escalation in `/reviews` |
| `REJECTED` | A reviewer rejected a gate (terminal) | — |
| `CANCELLED` | A reviewer rejected an escalation (terminal) | — |

## First responders: where to look

Sign in to the console first (`/login`); the `curl` examples take the same
credentials over HTTP Basic.

1. **`/executions`** — every execution, newest first, with its full audit timeline
   (each step, artifact write, HITL decision, connector action, retry, escalation).
2. **`/reviews`** — the human inbox: pending gates **and** escalations awaiting a decision.
3. **`GET /api/status`** — engine enabled flag, connector configured/not-configured,
   registered flows. Same data as the `/status` console page.

   ```bash
   curl -sf -u "$API_USER:$API_PASSWORD" localhost:8080/api/status
   ```

4. **`GET /actuator/health`** — liveness/readiness. Public, but the detail is not:
   `db` and the `connectors` component appear only for an authenticated caller
   (`show-details: when-authorized`), so pass credentials — or read connector state
   from `/status` instead, which is where it is meant to be read.

   ```bash
   curl -sf -u "$API_USER:$API_PASSWORD" localhost:8080/actuator/health
   ```

   `connectors` is always `UP`; an unconfigured connector is expected advisory
   mode, not a failure.

5. **`GET /actuator/prometheus`** — the metrics below. Requires Basic unless
   `FACTORY_AUTH_METRICS_PUBLIC=true`.

## Key metrics & suggested alerts

Metrics are Prometheus-exposed (dots → underscores; counters get `_total`). Thresholds
are starting points — tune to your traffic.

| Signal (Prometheus name) | Watch for | Suggested alert |
|---|---|---|
| `factory_steps_escalations_total` | Steps exhausting the retry budget | rate > 0 sustained 15m → page (systemic failure, e.g. a down dependency or bad prompt) |
| Unresolved escalations piling up | `factory_steps_escalations_total` rising while `factory_executions_terminal_total` stays flat | sustained divergence → escalated runs are not being worked; check `/reviews`. (`FAILED_ESCALATED` is deliberately **not** an outcome tag — it is resumable, so counting it would double-count runs that later resolve.) |
| `factory_connector_actions_total{outcome="failed"}` | Jira/GitHub/TestRail sync failures | rate > 0 sustained → check the connector/credentials |
| `factory_agent_step_duration_seconds` (p99) | Slow workers (LLM latency, Karate) | p99 approaching `max-step-heartbeat-seconds` → legitimate steps are outliving the heartbeat ceiling and will be reaped mid-run. `lease-timeout-seconds` is **not** the relevant bound: a running step keeps refreshing its lease |
| `hikaricp_connections_pending` / `_timeout_total` | DB pool saturation | pending > 0 sustained, or any timeout increase → raise `FACTORY_DB_POOL_MAX` (see performance.md) |
| Executions stuck in `RUNNING` beyond the lease | Crashed workers not being reaped | count(status=RUNNING, updated_at < now − lease) > 0 for > 2× reap interval → investigate the reaper. A *live* step never qualifies: its heartbeat keeps `updated_at` fresh |
| Pending-review age | HITL backlog | oldest `PENDING` `hitl_review` older than your SLA → notify reviewers |
| `factory_executions_terminal_total{outcome="rejected"|"cancelled"}` | Reviewers rejecting output | spike → prompt/quality regression |

Also alert on the standard actuator readiness probe flipping (DB unreachable) and JVM/GC basics.

## Recovering a stuck execution

**Symptom: an execution sits in `RUNNING` and its audit timeline shows no recent step.**

Two mechanisms interact here, and the order matters:

- **The step-lease heartbeat** (`StepLeaseHeartbeat`) refreshes the row's `updated_at`
  every `factory.engine.heartbeat-interval-seconds` (default **60s**) for as long as the
  step is running. A live step — however slow — is therefore **never** reaped. Refreshing
  stops after `factory.engine.max-step-heartbeat-seconds` (default **3600s / 60 min**),
  which is what keeps a step wedged on a hung socket recoverable at all; the engine logs
  `Stopped refreshing the lease of execution … after 3600s; if its step is wedged the
  reaper will now recover it` at that moment.
- **The lease reaper** (`ExecutionClaimService.reapStale`, every
  `factory.engine.reap-interval-ms`, default 30s) then returns any `RUNNING` row whose
  `updated_at` is older than `factory.engine.lease-timeout-seconds` (default **1800s / 30
  min**) to `PENDING`, and a poller re-runs the step. Re-running a step is safe — workers
  write new immutable artifact versions, never overwrite, and non-idempotent side effects
  are skipped on the strength of their audit-ledger entry.

**Expected recovery time depends on how the worker died:**

| Failure | Recovery |
|---|---|
| The process crashed (heartbeat thread gone with it) | lease timeout, ~30 min — or immediately on restart, via `reclaim-running-on-startup` |
| The step is wedged in-process (hung socket, deadlock) while the JVM lives | `max-step-heartbeat-seconds` **then** `lease-timeout-seconds` — **~90 min on defaults** |

- **Do not raise `FACTORY_ENGINE_LEASE_TIMEOUT_SECONDS` to accommodate long steps.** The
  heartbeat already protects them; the only thing a higher lease timeout buys is a slower
  recovery of genuinely dead workers.
- **The knob for long steps is `FACTORY_ENGINE_MAX_STEP_HEARTBEAT_SECONDS`** — it must
  exceed the slowest legitimate step (the LLM read timeout, Karate's 15-minute cap).
  Below that, a legitimately slow step stops heartbeating and is reaped mid-run, wasting
  work. Raise `FACTORY_LLM_READ_TIMEOUT` first if the LLM call is what runs long: keeping
  the model-call bound under the heartbeat ceiling is what makes a hung completion fail
  its step rather than sit out the ceiling.
- If you cannot wait out the recovery window and are certain the worker is dead, manually
  requeue (see below) — that skips both timers.

**Manual requeue (last resort, requires DB access):**

Requeuing bypasses the heartbeat, so a still-live step would be driven a **second
time concurrently**. The guarded step-advance keeps the late-returning original driver
from corrupting the cursor, and ledger-keyed side effects are not repeated, but any
work in flight is duplicated. Confirm the worker is dead first: an `updated_at` older
than a couple of `heartbeat-interval-seconds` means nothing is refreshing the lease.

```sql
-- Confirm it is genuinely stuck first — updated_at must be well past the heartbeat
-- interval (default 60s), which is the proof no live step is refreshing it:
SELECT id, status, current_step_index, updated_at, now() - updated_at AS since_refresh
  FROM pipeline_execution WHERE id = '<uuid>';

-- Return it to the queue.
UPDATE pipeline_execution SET status = 'PENDING', next_run_at = now() WHERE id = '<uuid>' AND status = 'RUNNING';
```

**Symptom: a step keeps failing and lands in `FAILED_ESCALATED`.** It has an escalation
review in `/reviews`. Investigate the error (shown in the review package and the audit
timeline). Then either **reject** (→ `CANCELLED`) or fix the underlying cause and
**approve** — approving resets that step's retry budget and re-runs it from the same step.

## Draining for a deploy / rollout

Graceful shutdown (OPS-2) is enabled. On `SIGTERM`:

1. `ExecutionPoller` stops claiming new work immediately (on `ContextClosedEvent`).
2. The web server drains in-flight HTTP for up to
   `spring.lifecycle.timeout-per-shutdown-phase` (default **60s**).
3. The engine pool finishes already-dispatched steps for up to
   `factory.engine.shutdown-await-seconds` (default **30s**); anything still running past
   that is abandoned as a `RUNNING` row for the next instance to recover.

**How an abandoned row is recovered depends on one setting.** With
`factory.engine.reclaim-running-on-startup` **on (the default, single-instance only)** the
new instance requeues every `RUNNING` row on its first poll, ignoring lease age — recovery
is immediate, not lease-timeout slow. With it **off** — the required setting for a
multi-instance deployment, where a peer's live work is also `RUNNING` (see
[Cost controls](#cost-controls-cost-1)) — recovery instead waits out
`lease-timeout-seconds`.

**Set the container `terminationGracePeriodSeconds` ≥ 60 + 30 = ~90s**, or the
orchestrator will `SIGKILL` mid-drain. Correctness holds either way; what the grace period
buys is fewer *re-run steps*, because an abandoned step restarts from its beginning and
only the side effects keyed in the audit ledger (see
[Connector degradation](#connector-degradation)) are skipped rather than repeated. A long
Karate step in flight at shutdown will exceed the 30s drain and be re-run; this is the
intended bounded-deploy tradeoff.

## Cost controls (COST-1)

- **Dedup** (`factory.limits.dedup.*`, on by default) collapses re-fired identical
  triggers within the window onto the first execution; look for `EXECUTION_DEDUPED` audit
  events if a run you expected didn't start. **Manual triggers are exempt**: a deliberate
  `POST /api/triggers/manual` is never silently collapsed unless the caller opts in by
  sending the optional `dedupKey` request field (trimmed; max 255 chars, else 422; blank
  treated as absent).
- **Concurrency cap** (`factory.limits.max-concurrent-executions`, default 8) bounds
  simultaneous `RUNNING` executions; excess waits in `PENDING`. **The cap is
  per-instance**: the count-then-claim is atomic within a single JVM's single-threaded
  poller, but N horizontally-scaled instances each enforce their own cap, so total
  fleet concurrency can reach `cap × instances`. Milestone 1 targets a single instance;
  for multi-instance, size the cap as `desired_total / instances`, or treat a
  distributed cap (advisory lock / partitioned claiming) as a follow-up. On a clean
  restart the startup lease-reclaim (`factory.engine.reclaim-running-on-startup`, on by
  default, **single-instance only**) requeues crashed `RUNNING` rows so they don't
  consume cap slots — disable it when running multiple instances.
- **Daily budget** (`factory.limits.max-executions-per-day`, default 200) — once reached,
  new triggers get **HTTP 429** and an `EXECUTION_BUDGET_EXCEEDED` audit event. Raise the
  limit or wait for the UTC day boundary. (This is a spend guardrail; a legitimate burst
  that hits it means the budget is too low for real traffic.) Like the concurrency cap,
  the check is check-then-act, so a concurrent burst — or one event fanning out to
  several flows on the last slot — can overshoot the budget by a few executions; the
  enforcement is all-or-nothing per trigger event, never partial.

## Retention (DATA-1)

Off by default (`factory.retention.enabled=false`) — **the database grows without bound
until you turn it on.** When enabled, `RetentionService` purges terminal execution trees
(`COMPLETED`/`REJECTED`/`CANCELLED` and descendants) older than `ttl-days` on the
`run-cron` schedule, in batches of `batch-size`. `FAILED_ESCALATED` runs are **never**
purged (resumable). **`audit_event` is never purged** — it is the append-only compliance
record; purges are themselves audited as `RETENTION_PURGED`. Enable it in any long-lived
deployment and confirm the nightly `Retention purge removed …` log line.

## Connector degradation

Unconfigured connectors are expected: the flow still completes and every skipped external
sync is audited `CONNECTOR_SKIPPED` and counted in
`factory_connector_actions_total{outcome="skipped"}`.

A connector configured but **failing** is different — `outcome="failed"` is a real
problem (bad credentials, outage). The Test Factory finalizer records each failed sync,
finishes the remaining ones, and then **throws**, so the step retries under the flow's
retry policy and finally escalates into `/reviews` rather than completing a run whose
external state was never written. Fix the cause and approve the escalation to re-run the
step; side effects that already succeeded are skipped on the strength of their
`CONNECTOR_ACTION` ledger entry, so the retry does not duplicate a TestRail case, a pull
request or a Jira comment.

**Amending an artifact at that escalation deliberately re-syncs.** The finalizer's ledger
keys are content-addressed on the artifact each connector consumes, so approving without
edits skips everything as above — but an amendment is a new artifact version, hence a new
key, and only the connector reading it runs again:

| Amended at the escalation | What re-syncs |
|---|---|
| `test_plan.md` | a fresh set of TestRail cases and a fresh run (with the results re-posted onto it in executed mode) — the cases already created keep the superseded wording and are not edited from here |
| `test_scripts.md` | another commit onto the same branch and pull request |
| `test_results.md` | another Jira comment, plus the results re-posted onto the existing TestRail run (executed mode only — advisory results never reach TestRail) |

The Jira transition is keyed to no artifact and never replays. The alternative would be
worse: every key already recorded, so the correction is silently dropped and the sync
still reported as done. Approve without editing the package if you want the plain
skip-everything retry.
