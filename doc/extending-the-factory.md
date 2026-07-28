# Extending the Factory — Developer Guide

This guide is for teams building their own automation on the Factory platform:
new flows, new agent workers, new prompts, new connectors, and adopting the
platform on a project of your own. It assumes you can read Java/Spring and have
completed the [README quickstart](../README.md#quickstart) once.

Companion documents:

- [Flow descriptor reference](flow-descriptor-reference.md) — every YAML field,
  with the validation rules the platform enforces at startup.
- [Platform design overview](design-overview.md) — the architecture and the
  principles behind it (why flows are data, why artifacts are immutable, …).
- [Operations runbook](runbook.md) — running the platform in production.

Contents:

1. [The mental model](#1-the-mental-model)
2. [Repository layout — where things go](#2-repository-layout--where-things-go)
3. [Tutorial: build a new flow end to end](#3-tutorial-build-a-new-flow-end-to-end)
4. [Triggers](#4-triggers)
5. [Agent workers in depth](#5-agent-workers-in-depth)
6. [Prompts](#6-prompts)
7. [Artifacts](#7-artifacts)
8. [HITL gates](#8-hitl-gates)
9. [Sub-flows: composing flows](#9-sub-flows-composing-flows)
10. [Quality controls](#10-quality-controls)
11. [Connectors](#11-connectors)
12. [Per-flow configuration](#12-per-flow-configuration)
13. [Testing your flow](#13-testing-your-flow)
14. [Running, observing, debugging](#14-running-observing-debugging)
15. [Adopting the Factory on a new project](#15-adopting-the-factory-on-a-new-project)
16. [Design rules checklist](#16-design-rules-checklist)

## 1. The mental model

Five ideas explain everything else in this guide:

1. **Flows are data.** A flow is a YAML *plugin descriptor*
   (`src/main/resources/flows/<flow>.yaml`): its triggers, its ordered
   `agent_chain` of steps, its retry policy. The control plane
   (`factory-core`) executes descriptors generically — it cannot even compile
   against a flow module. You never modify the engine to add a flow.
2. **Agents are stateless workers.** An `AgentWorker` is a Spring bean with a
   stable `id()` and one `execute(AgentContext)` method. Flows reference
   workers by id; any flow can reuse any worker. Workers hold no per-execution
   state.
3. **Artifacts are the only channel.** Workers communicate exclusively through
   named, immutable, versioned documents in the artifact store. A worker sees
   *only* the artifacts its step declares as `inputs` and must produce the
   artifacts declared as `outputs` — the engine enforces both. Corrections
   (including human amendments) become new attributed versions, never
   overwrites.
4. **Humans gate consequential steps.** `HITL_GATE` steps park the execution
   until a reviewer approves, amends, or rejects. Retry-budget exhaustion
   escalates into the same review inbox. Nothing merges, deploys, or syncs
   externally without having passed a gate.
5. **Everything is audited.** Every step, artifact write, decision, connector
   action, and sub-flow invocation lands in an append-only audit log (the DB
   blocks UPDATE/DELETE on it).

### How one execution runs

```
trigger event                 (manual API / Jira webhook / GitHub webhook / parent flow)
     │
     ▼
PipelineRouter                matches event type + JSON-pointer filters against every
                              registered flow's trigger contracts; validates required
                              inputs; applies dedup + daily budget; creates a
                              PipelineExecution (status PENDING)
     │
     ▼
ExecutionPoller               claims runnable executions from the DB
                              (FOR UPDATE SKIP LOCKED + lease reaper for crash recovery)
     │
     ▼
ExecutionEngine               advances the step cursor through agent_chain:
  ├─ AGENT       resolve worker by id → build scope-bounded AgentContext →
  │              execute → run StepPostProcessors → persist output artifacts →
  │              advance (failure: retry with backoff, then escalate to review)
  ├─ HITL_GATE   open review, park execution (AWAITING_HITL) until decision
  └─ SUB_FLOW    start child execution, park parent (AWAITING_SUBFLOW) until
                 the child completes, then copy mapped outputs up
     │
     ▼
COMPLETED                     (or REJECTED / CANCELLED / FAILED_ESCALATED)
```

Execution statuses: `PENDING → RUNNING → AWAITING_HITL / AWAITING_SUBFLOW →
… → COMPLETED`, with `FAILED_ESCALATED` (resumable via its escalation review),
`REJECTED` (reviewer rejected a gate) and `CANCELLED` (reviewer rejected an
escalation) as the other terminal states. The full lifecycle is in the
[runbook](runbook.md#execution-lifecycle--statuses).

### What the framework enforces (you cannot opt out)

- Scope isolation: a worker physically receives only its declared `inputs`
  (plus the trigger payload if it declares the reserved input `$trigger`).
- Output scope: a step fails if the worker does not return every declared
  output artifact, or returns one it did not declare.
- Immutability: artifact writes are insert-only versioning; audit is
  append-only.
- Bounded retry: per-step attempts counted against the flow's `retry_policy`;
  exhaustion escalates to human review — never silent failure, never infinite
  loops.
- Startup validation: unknown worker ids, unknown sub-flow references,
  duplicate flow ids, duplicate step ids, or unknown YAML fields in a
  descriptor all **fail application startup** with a `FlowValidationException`.
  A broken plugin cannot be half-loaded.

## 2. Repository layout — where things go

```
factory-core/                control plane: registry, router, engine, HITL, artifact
                             store, audit. NEVER touched to add a flow.
factory-connectors/          shared REST clients for external systems (Jira, GitHub,
                             TestRail) + "not configured" fallbacks. Extend here to add
                             a connector any flow can use.
factory-agents/              LLM worker base class, prompt loading, frontmatter codec,
                             framework-wide output post-processors.
factory-flow-test-factory/   Test Factory plugin — the reference implementation to copy from.
factory-app/                 composition root: REST API, webhook adapters, HITL web UI,
                             Flyway migrations, application.yaml. Aggregates all modules.
```

Dependency direction is strict: `core ← connectors ← agents ← flow modules ←
app`. A flow module depends on `factory-agents` (which brings in `factory-core`)
and, if it needs external systems, `factory-connectors`. Only `factory-app`
knows the full set of plugins.

Extension points, by what you want to add:

| You want to add | You write | Where |
|---|---|---|
| A new flow | YAML descriptor + worker beans | new `factory-flow-*` module |
| A new agent capability | `AgentWorker` bean (LLM-backed or deterministic) | your flow module (or `factory-agents` if genuinely flow-agnostic) |
| Prompts | `prompts/<worker-id>/system.md` + `user.md` | your flow module's resources |
| An external integration | connector interface + REST impl + fallback | `factory-connectors` |
| An output quality check | `StepPostProcessor` bean | your flow module (self-scoped) or `factory-agents` (global) |
| An amendment format check | `ArtifactAmendmentValidator` bean | your flow module |
| A new webhook source | endpoint that normalises to `TriggerEvent` | `factory-app` (`WebhookController` or a sibling) |

## 3. Tutorial: build a new flow end to end

We will build **`release-notes`**: a manually triggered flow that drafts
release notes from a commit list with an LLM, passes them through an editorial
HITL gate, and publishes the approved notes as a GitHub pull request (or
records a skip when GitHub is not configured). Two workers — one LLM-backed,
one deterministic — plus one gate: it exercises every mechanism you will use
in real flows.

All names below follow the conventions of the existing Test Factory module
([factory-flow-test-factory](../factory-flow-test-factory)); when in doubt,
open the corresponding Test Factory file next to this tutorial.

### 3.1 Scaffold the module

Create `factory-flow-release-notes/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.folio.factory</groupId>
    <artifactId>factory-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>factory-flow-release-notes</artifactId>
  <name>Factory Flow — Release Notes</name>

  <dependencies>
    <dependency>
      <groupId>org.folio.factory</groupId>
      <artifactId>factory-agents</artifactId>
    </dependency>
    <dependency>
      <groupId>org.folio.factory</groupId>
      <artifactId>factory-connectors</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Then register the module in three places:

1. Root [pom.xml](../pom.xml) — add `<module>factory-flow-release-notes</module>`
   to `<modules>` and a matching entry in `<dependencyManagement>` (copy the
   `factory-flow-test-factory` entry).
2. [factory-app/pom.xml](../factory-app/pom.xml) — add the module as a
   dependency. This is what makes the plugin part of the deployed application.
3. Nothing else. No engine, router, or gateway changes.

Java packages live under `org.folio.factory.<flow>` (Test Factory uses
`org.folio.factory.testfactory`; we'll use `org.folio.factory.relnotes`). The
application scans all of `org.folio.factory`, so your `@Configuration` is
picked up automatically once the jar is on the classpath.

### 3.2 Write the flow descriptor

`factory-flow-release-notes/src/main/resources/flows/release-notes.yaml`:

```yaml
id: release-notes
name: Release Notes
version: 1.0.0
triggers:
  - event_type: manual
input_schema:
  required: [repository]
output_schema:
  artifacts: [release_notes.md, publish_report.md]
retry_policy:
  max_attempts: 3
  backoff_seconds: [30, 120, 300]
agent_chain:
  - step_id: draft
    type: AGENT
    worker_id: release-notes-drafter
    inputs: ["$trigger"]
    outputs: [release_notes.md]
    config:
      tone: concise

  - step_id: editorial-review
    type: HITL_GATE
    gate:
      gate_id: gate-editorial
      title: "Editorial review: release notes"
      review_instructions: >
        Check the draft against the commit list for accuracy and completeness.
        Edit the notes inline (AMEND) for tone or content fixes; REJECT if the
        draft is unsalvageable.
      reviewed_artifacts: [release_notes.md]

  - step_id: publish
    type: AGENT
    worker_id: release-notes-publisher
    inputs: [release_notes.md, "$trigger"]
    outputs: [publish_report.md]
```

Reading it like the engine does:

- `triggers` — this flow starts on manual initiation
  (`POST /api/triggers/manual` with `"flowId": "release-notes"`). §4 covers
  webhook triggers.
- `input_schema.required` — the router rejects triggers whose payload lacks a
  non-null top-level `repository` field. (This is the only enforced part of
  the schema; the rest is documentation.)
- `agent_chain` — three steps. `draft` may read only the trigger payload
  (the reserved input `$trigger`) and must produce `release_notes.md`.
  `publish` may read the *latest* version of `release_notes.md` — which after
  the gate is the reviewer-amended version, if any — plus the trigger.
- `config` — arbitrary per-step key/values handed to the worker
  (`context.configString("tone", "concise")`). The same worker can behave
  differently in different flows without code changes.
- Unknown fields fail startup (strict parsing), so typos surface immediately —
  see the [descriptor reference](flow-descriptor-reference.md) for every field.

### 3.3 The LLM worker

`org/folio/factory/relnotes/model/ReleaseNotes.java` — the structured output
the model must return. Spring AI derives a JSON schema from this record and
appends format instructions to the prompt; parsing back is automatic:

```java
package org.folio.factory.relnotes.model;

import java.util.List;

public record ReleaseNotes(String title, List<String> highlights, String body) {
}
```

`org/folio/factory/relnotes/worker/ReleaseNotesDrafterWorker.java`:

```java
package org.folio.factory.relnotes.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.relnotes.model.ReleaseNotes;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

public class ReleaseNotesDrafterWorker extends AbstractLlmAgentWorker {

    public static final String ID = "release-notes-drafter";

    private final FrontmatterCodec frontmatterCodec;

    public ReleaseNotesDrafterWorker(ChatClient chatClient, FrontmatterCodec frontmatterCodec) {
        super(chatClient);
        this.frontmatterCodec = frontmatterCodec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode payload = context.triggerPayload();
        if (payload == null || payload.path("commits").isEmpty()) {
            throw new AgentExecutionException("release-notes-drafter requires a trigger payload with commits");
        }

        ReleaseNotes notes = callForEntity(
                Map.of("commits_json", payload.path("commits").toString(),
                        "tone", context.configString("tone", "concise")),
                ReleaseNotes.class);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repository", payload.path("repository").asString(""));
        metadata.put("title", notes.title());
        metadata.put("highlights", notes.highlights());
        return AgentResult.of("release_notes.md",
                frontmatterCodec.render(metadata, "# " + notes.title() + "\n\n" + notes.body()));
    }
}
```

What the base class gives you:

- `callForEntity(variables, Type.class)` renders
  `prompts/release-notes-drafter/system.md` and `user.md` with the variables,
  calls the model, and binds the response to your record — retrying **once**
  inside the same step attempt if the output is unparseable (a second parse
  failure throws `AgentExecutionException`, which counts against the step's
  retry budget).
- `callForText(variables)` for free-form Markdown output.
- Throwing `AgentExecutionException` (or any exception) fails the step: the
  engine retries with backoff and escalates to human review when the budget is
  exhausted. Validate the model's output and throw when it is unusable —
  that is the self-correction loop.

### 3.4 The prompts

Prompts live on the classpath at `prompts/<worker-id>/system.md` and
`user.md`. `{{placeholders}}` are substituted from the variables map — nothing
else is templated (no loops, no conditionals; see §6 for the exact rules).

`src/main/resources/prompts/release-notes-drafter/system.md`:

```markdown
You are the Release Notes Agent of the AI SDLC Factory. Your single
responsibility is to turn a list of commits into clear release notes for
library staff and developers.

Rules:
- Write in a {{tone}} tone.
- Group related commits into a single highlight; ignore merge commits and
  pure formatting changes.
- Never invent changes that are not in the commit list.
- highlights are one line each; body is full Markdown with sections for
  Features, Fixes and Internal changes (omit empty sections).
```

`src/main/resources/prompts/release-notes-drafter/user.md`:

```markdown
Draft release notes for these commits (JSON array of {sha, message}):

{{commits_json}}
```

### 3.5 The deterministic worker

Not every worker calls an LLM — side-effecting steps in particular should be
plain deterministic Java. The publisher reads the *approved* notes and pushes
them through the shared GitHub connector, skipping cleanly when the connector has
no credentials (the platform convention — unconfigured integrations skip and
report, they never fail the flow) and refusing to complete when a configured one
fails:

`org/folio/factory/relnotes/worker/ReleaseNotesPublisherWorker.java`:

```java
package org.folio.factory.relnotes.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.core.service.ArtifactStore;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ReleaseNotesPublisherWorker implements AgentWorker {

    public static final String ID = "release-notes-publisher";

    private static final String PUBLISH_PR = "github.pr";

    private final GitHubConnector gitHub;
    private final FrontmatterCodec frontmatterCodec;
    private final WorkerAuditSink auditSink;

    public ReleaseNotesPublisherWorker(GitHubConnector gitHub, FrontmatterCodec frontmatterCodec,
                                       WorkerAuditSink auditSink) {
        this.gitHub = gitHub;
        this.frontmatterCodec = frontmatterCodec;
        this.auditSink = auditSink;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        ArtifactContent notes = context.requireInput("release_notes.md");
        String repository = context.triggerPayload().path("repository").asString("");
        String branch = "release-notes/draft";
        // Keyed on the notes this call transmits, not on the connector alone: a
        // reviewer who amends them at the escalation gate must get the amended
        // version pushed, not the ledger's "already published".
        String actionKey = PUBLISH_PR + "@" + ArtifactStore.sha256(notes.content()).substring(0, 12);

        // A previous attempt of this step may already have opened the PR; the
        // ledger entry carries its URL.
        Optional<String> published = auditSink.completedDetail(context.executionId(), "github", actionKey);
        if (published.isPresent()) {
            return report(repository, "done", published.get());
        }

        try {
            gitHub.createBranch(repository, "main", branch);
            gitHub.commitFiles(repository, branch, Map.of("RELEASE_NOTES.md", notes.content()),
                    "Add release notes (AI SDLC Factory)");
            String prUrl = gitHub.createPullRequest(repository, branch, "main",
                    "Release notes", "Generated by the release-notes flow.");
            auditSink.connectorAction(context.executionId(), context.stepId(), ID,
                    "github", actionKey, "publish PR", prUrl);
            return report(repository, "done", prUrl);
        } catch (ConnectorNotConfiguredException e) {
            auditSink.connectorSkipped(context.executionId(), context.stepId(), ID,
                    "github", actionKey, "publish PR", e.getMessage());
            return report(repository, "skipped", e.getMessage());
        } catch (RestClientException e) {
            auditSink.connectorFailed(context.executionId(), context.stepId(), ID,
                    "github", actionKey, "publish PR", e.getMessage());
            throw new AgentExecutionException("Could not publish release notes to " + repository, e);
        }
    }

    private AgentResult report(String repository, String status, String detail) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repository", repository);
        metadata.put("status", status);
        return AgentResult.of("publish_report.md", frontmatterCodec.render(metadata,
                "# Publish report\n\n- GitHub: **" + status + "** — " + detail + "\n"));
    }
}
```

Three things there are platform contracts, not style:

- **`WorkerAuditSink`, never `AuditLog`.**
  [`AuditLog`](../factory-core/src/main/java/org/folio/factory/core/service/AuditLog.java)
  is the control plane's own writer: it accepts any event type under any actor,
  including `system`. A flow plugin holding it could make the ledger's actor
  column prove nothing. The
  [SPI](../factory-core/src/main/java/org/folio/factory/core/agent/WorkerAuditSink.java)
  narrows that to the three connector outcomes and derives the actor from the
  worker id you pass (`worker:release-notes-publisher`), so a plugin can neither
  forge an engine event nor attribute one to the platform. `AuditLog` is still a
  public bean on your compile classpath — injecting it compiles, and writes
  `system`-actor rows with no action key. Don't.
- **A stable `actionKey`.** It is the ledger's idempotency key, not a caption:
  `github.pr`, `jira.comment` — never human-readable prose (the label is the
  separate `label` argument; a blank key is rejected outright). **One key per
  irreversible call.** A key spanning several cannot be written until the last
  one succeeds, so a failure in between leaves the earlier ones unrecorded and
  the retry repeats them. Stable means *stable for the same input*: a reviewer
  can amend the step's input artifacts at its escalation gate, and under a bare
  key the ledger entry is already recorded — the retry skips the call and reports
  the sync done while the correction never leaves the building. Where that
  matters, fold the consumed artifact's content hash into the key
  (`github.pr@<sha12>`, as Test Factory's finalizer does) so only the connector
  reading the amended artifact re-runs.
- **`completedDetail` before the side effect.** A step re-runs from scratch on
  retry, lease reap, or an approved escalation, and a crash midway leaves it
  without any artifact write at all — so the ledger, not the report artifact, is
  the durable record of what already happened. It returns the recorded detail
  rather than a boolean because a re-run usually needs the identifier the skipped
  call produced: the PR URL here, or the TestRail case id that Test Factory's
  finalizer needs in order to build the run without creating the case twice.

And the three-way error split, which §11 covers in full:

| Outcome | What the worker does |
|---|---|
| Connector not configured | `connectorSkipped`, report `skipped`, complete — the zero-credentials path. An unconfigured integration never fails a flow. |
| Configured but failing | `connectorFailed`, then **throw**. The attempt is consumed, the engine retries under the flow's retry policy and finally escalates to a human, instead of completing a run whose external state was never written. |
| Success | `connectorAction` under the action key, then continue. |

### 3.6 Wire the beans

`org/folio/factory/relnotes/ReleaseNotesConfiguration.java`:

```java
package org.folio.factory.relnotes;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.relnotes.worker.ReleaseNotesDrafterWorker;
import org.folio.factory.relnotes.worker.ReleaseNotesPublisherWorker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReleaseNotesConfiguration {

    @Bean
    public ReleaseNotesDrafterWorker releaseNotesDrafterWorker(ChatClient.Builder chatClientBuilder,
                                                               FrontmatterCodec frontmatterCodec) {
        return new ReleaseNotesDrafterWorker(chatClientBuilder.build(), frontmatterCodec);
    }

    @Bean
    public ReleaseNotesPublisherWorker releaseNotesPublisherWorker(GitHubConnector gitHubConnector,
                                                                   FrontmatterCodec frontmatterCodec,
                                                                   WorkerAuditSink auditSink) {
        return new ReleaseNotesPublisherWorker(gitHubConnector, frontmatterCodec, auditSink);
    }
}
```

Inject `WorkerAuditSink` by its interface — `LedgerWorkerAuditSink` in
`factory-core` is the single implementation and is auto-configured, so there is
nothing to declare.

Every `AgentWorker` bean is collected into the `AgentWorkerRegistry` at
startup. Two rules: worker ids must be unique across the whole application
(a duplicate fails startup), and every `worker_id` referenced by any
registered flow must resolve to a bean (a dangling reference fails startup).

### 3.7 Run it

```bash
docker compose up -d                     # PostgreSQL
export ANTHROPIC_API_KEY=sk-ant-...
mvn install -DskipTests                  # -pl resolves siblings from ~/.m2
mvn spring-boot:run -pl factory-app

# /api/** is authenticated. With FACTORY_AUTH_USERS unset the startup log prints a
# generated password for 'admin'; export your own accounts to keep it stable.
curl -sf -u admin:"$ADMIN_PASSWORD" -X POST localhost:8080/api/triggers/manual \
  -H 'Content-Type: application/json' \
  -d '{
        "flowId": "release-notes",
        "payload": {
          "repository": "acme/widget-service",
          "commits": [
            {"sha": "a1b2c3", "message": "feat: add CSV export to reports"},
            {"sha": "d4e5f6", "message": "fix: NPE when report has no rows"}
          ]
        }
      }'
```

The response is `202 {"executionId": "..."}`. Watch the execution advance at
<http://localhost:8080/executions>; when it parks at `editorial-review`, open
<http://localhost:8080/reviews>, read the draft, amend it if you like, approve
— and the publisher runs with your amended version. With no GitHub credentials
configured you get a completed execution whose `publish_report.md` says
`skipped`, and a `CONNECTOR_SKIPPED` audit event: the zero-credentials path
works for your flow exactly as it does for the Test Factory flow.

Startup log lines to look for:

```
Flow registry loaded 2 flow(s): [test-factory, release-notes]
Agent worker library loaded 7 worker(s): [..., release-notes-drafter, release-notes-publisher]
```

### 3.8 What you did NOT have to build

Retry with backoff, escalation to a human on repeated failure, crash recovery
(a killed JVM resumes the execution via the poller's lease reaper), artifact
versioning and attribution, the review UI and decision API, audit trail,
dedup, execution budgets, size caps, metrics per worker
(`/actuator/prometheus`), structured-log correlation (`executionId`/`stepId`
in every line) — all of it applies to your flow automatically because it is
descriptor-driven.

## 4. Triggers

A flow declares *trigger contracts*; the router matches every incoming
`TriggerEvent` (a `type` string plus a JSON payload) against all registered
contracts and starts an execution per matching flow.

```yaml
triggers:
  - event_type: manual
  - event_type: jira.issue.transitioned
    filters:
      "/issue/fields/status/name": "Ready for QA"
```

- `event_type` is an exact string match.
- `filters` map **JSON pointer → expected value**: the pointer is resolved
  into the event payload and compared as a string. All filters must match.
  A pointer that resolves to an object/array never matches (and logs a
  warning — it is almost always a descriptor typo).

### Event types that exist today

| `event_type` | Emitted when | Source |
|---|---|---|
| `manual` | `POST /api/triggers/manual` — but note manual triggers name the flow explicitly and bypass contract matching; declare `manual` anyway to document that the flow supports it | [TriggerController](../factory-app/src/main/java/org/folio/factory/app/web/TriggerController.java) |
| `jira.issue.transitioned` | Jira webhook with `webhookEvent: jira:issue_updated` | [WebhookController](../factory-app/src/main/java/org/folio/factory/app/web/WebhookController.java) |
| `jira.issue.created` | Jira webhook with `webhookEvent: jira:issue_created` | same |
| `jira.event` | any other Jira webhook | same |
| `github.<event>` | GitHub webhook; `<event>` is the `X-GitHub-Event` header value, e.g. `github.push`, `github.pull_request` | same |

The webhook adapters also *normalise* payloads to internal contract fields —
e.g. raw Jira bodies carry the key at `issue.key`, and the adapter copies it to
top-level `issueKey` so flow `input_schema` contracts stay independent of
external formats. If your flow consumes a new external system, add the
adapter endpoint in `factory-app` (translate the external body into
`TriggerEvent.of(type, source, payload)` and hand it to
`PipelineRouter.route`) — that is an app-level adapter, not a control-plane
change.

### Validation, dedup, and budgets (applied by the router, flow-agnostic)

- **Required inputs** — `input_schema.required` lists top-level payload fields
  that must be present and non-null; both webhook-matched and manual triggers
  are validated.
- **Dedup** — webhook triggers derive an identity key from the payload
  (`factory.limits.dedup.id-pointers`, default `/issueKey`, falling back to a
  payload hash); a re-fired trigger within the dedup window (default 10m)
  returns the existing execution instead of starting a duplicate. Manual
  triggers are **never** deduped unless the request supplies a `dedupKey`.
- **Budgets and caps** — a daily execution budget (HTTP 429 once exceeded)
  and a trigger payload size cap. See the README configuration table and the
  [runbook](runbook.md#cost-controls-cost-1).

## 5. Agent workers in depth

### The contract

```java
public interface AgentWorker {
    String id();                                            // referenced as worker_id in descriptors
    AgentResult execute(AgentContext context) throws AgentExecutionException;
}
```

[`AgentContext`](../factory-core/src/main/java/org/folio/factory/core/agent/AgentContext.java)
is everything the worker may see:

| Field | Content |
|---|---|
| `executionId`, `stepId` | identity, for audit records and logs |
| `inputs` | map of artifact name → `ArtifactContent(name, version, contentType, content)` — **only** the step's declared `inputs`, always the latest version |
| `triggerPayload` | the trigger's JSON — only if the step declares the reserved input `$trigger` |
| `config` | the step's `config` map from the descriptor (`configString(key, default)` helper) |
| `expectedOutputs` | the step's declared `outputs` — useful for generic workers that produce whatever the flow asks for |

`context.requireInput("name")` throws with a self-explanatory message when the
artifact was not declared — use it rather than `inputs().get(...)`.

[`AgentResult`](../factory-core/src/main/java/org/folio/factory/core/agent/AgentResult.java)
is `outputs` (artifact name → content — must match the declared outputs
exactly; `AgentResult.of(name, content)` for the single-artifact case) plus
optional `metrics` (token counts, durations, …) which are recorded in the
audit log with the `STEP_COMPLETED` event. LLM workers can return
`resultWithUsage(name, content)` to carry the model call's
`promptTokens`/`completionTokens` into those metrics automatically.

### Failure semantics — design for them

Throwing from `execute` (an `AgentExecutionException`, a connector error, a
parse failure) fails the **attempt**:

1. The engine records `STEP_FAILED` and increments the step's retry counter.
2. Below `retry_policy.max_attempts`, the execution is re-queued after the
   configured backoff. **The whole step re-runs from scratch** — workers must
   be safe to re-execute (see idempotency below).
3. At the budget, the execution transitions to `FAILED_ESCALATED` and an
   escalation review (reserved gate id `escalation`) opens in the same inbox
   as regular gates, packaging the step's input artifacts and the error.
   A reviewer can APPROVE (reset the step's retry budget and try again —
   e.g. after fixing credentials) or REJECT (cancel the execution).

Consequences:

- **Validate aggressively, throw early.** A thrown exception is a controlled
  retry; a silently wrong artifact poisons every downstream step.
- **Make side effects replay-safe — and still fail the step when one didn't
  happen.** A retried step re-runs *everything* in it, so guard each irreversible
  call with `WorkerAuditSink.completedDetail(...)` under its own stable action
  key, content-addressed on the artifact that call transmits (§3.5): the ledger is
  the only record that survives a crash before the artifact write, and a
  content-blind key turns a reviewer's amendment at the escalation gate into a
  silent skip. Test Factory's finalizer is the reference — re-runnable GitHub
  calls (branch-exists and PR-exists treated as success), one action key per
  TestRail case, per run and per result set, and a configured-but-failing sync
  recorded as `failed` and then **thrown**, so the run escalates instead of
  completing with external state that was never written. A failed sync swallowed
  into the report artifact is a run that looks green and isn't.
- Artifact writes need no such care — a re-run writes new versions; old ones
  are never corrupted.

### LLM-backed workers

Extend
[`AbstractLlmAgentWorker`](../factory-agents/src/main/java/org/folio/factory/agents/llm/AbstractLlmAgentWorker.java):

- Inject a `ChatClient` (build it from Spring's auto-configured
  `ChatClient.Builder`, one per worker bean). Workers are provider-agnostic —
  the concrete model comes from configuration (§15 for swapping providers).
- `callForEntity(vars, MyRecord.class)` — structured output bound to a Java
  record. Includes one in-attempt re-ask when the model returns unparseable
  JSON. Prefer records with primitive/String/List fields; keep them in a
  `model/` package.
- `callForText(vars)` — free-form output.
- Design rule: **one narrow responsibility per worker.** "Parse the story and
  produce a scope manifest" — not "do the whole QA process". Narrow workers
  are testable, reusable across flows, and produce reviewable artifacts.

### Deterministic workers

Implement `AgentWorker` directly (§3.5). Use them for connector syncs,
format conversions, running external processes — anything where an LLM adds
nothing but nondeterminism. Test Factory's `test-execution-agent` and finalizer are
both deterministic.

### Reuse across flows

Worker ids are global. Any flow may reference `release-notes-drafter` once it
exists — with its own `config`, its own input artifact contents, its own
position in a chain. Put a worker in `factory-agents` only when it is
genuinely flow-agnostic *and* prompt-free (or its prompts ship with it);
otherwise keep it in the flow module that owns its prompts.

## 6. Prompts

Layout — one directory per worker id, two files, on the classpath of the
worker's module:

```
src/main/resources/prompts/<worker-id>/system.md   role, rules, output constraints
src/main/resources/prompts/<worker-id>/user.md     the task + input data
```

Rendering rules
([`PromptLoader`](../factory-agents/src/main/java/org/folio/factory/agents/llm/PromptLoader.java)):

- Placeholders are `{{name}}`; values come from the map you pass to
  `callForEntity`/`callForText`. `toString()` is applied; `null` renders as
  empty.
- **Single pass**: placeholders inside substituted values are never
  re-expanded (input data cannot inject template directives).
- **Unknown placeholders are left visible** in the prompt rather than
  silently dropped — a template/variables mismatch shows up in the model's
  input (and usually its output) instead of disappearing.
- A missing prompt file fails the step at runtime with
  `Cannot load prompt template classpath:prompts/...` — the file name must
  match the worker id exactly.
- No loops or conditionals. If you need to iterate, serialise the collection
  (JSON) into one placeholder and let the model consume it — see
  `{{commits_json}}` above and Test Factory's `{{issue_json}}`.

Conventions that hold across the existing prompts (follow them):

- System prompt: state the worker's **single responsibility** in the first
  sentence, then numbered/bulleted hard rules, including the exact output
  expectations. The stub-LLM test pattern (§13) also keys on a distinctive
  role phrase in the system prompt — keep one.
- User prompt: short task statement + labelled input data. Data goes in the
  user prompt, rules go in the system prompt.
- **Synthetic data only**: instruct generation workers to use synthetic
  names/ids and never production-looking credentials or emails (see the Test Factory
  system prompts for wording). The framework's secret scanner (§10) backstops
  this, but the prompt is the first line of defence.
- Prompt changes are code changes: same PR review, and ideally a version note
  in the flow descriptor (`version:`) when behaviour shifts materially.

## 7. Artifacts

Artifacts are named documents (`test_plan.md`, `release_notes.md`, …) scoped
to one execution, stored with insert-only versioning:

- The engine persists a worker's outputs after post-processors pass; each
  write creates **version N+1**, attributed to the writing step
  (`created_by = step id`), a reviewer (`hitl:<reviewer>`), or a sub-flow copy
  (`subflow:<execution id>`).
- Readers always get the latest version; history stays queryable for audit
  (`GET /api/executions/{id}` shows every version).
- A write-time size cap (`factory.limits.max-artifact-bytes`, default 5 MB)
  protects the store.

### The canonical format: Markdown + YAML frontmatter

Workers hand each other *structured* data while reviewers amend *documents*.
The convention that reconciles the two —
[`FrontmatterCodec`](../factory-agents/src/main/java/org/folio/factory/agents/artifact/FrontmatterCodec.java):

```markdown
---
issue_key: ERM-1001
case_count: 2
cases: [...]
---

# Test Plan — ERM-1001
... human-readable body ...
```

- Frontmatter carries the fields downstream workers re-parse
  (`frontmatterCodec.parseMetadata(content, MyRecord.class)`).
- The body is what humans read and edit at gates.
- Parsing a malformed artifact throws `ArtifactFormatException` → step
  failure → retry/escalate, which is exactly what you want when an upstream
  (or an amendment) breaks the format. Pair the format with an
  [amendment validator](#amendment-validators) so malformed *human* edits are
  rejected at decision time instead.

For multi-file outputs, wrap them in a single bundle artifact with a manifest
in the frontmatter — see
[`ScriptBundleCodec`](../factory-flow-test-factory/src/main/java/org/folio/factory/testfactory/artifact/ScriptBundleCodec.java)
(frontmatter lists `{path, case_ids}` per file; the body carries each file in
a fenced block under a `## file:` heading). This keeps "agents communicate
only through artifacts" true even for generated file trees.

Naming: lowercase snake_case with an extension reflecting the content
(`scope_manifest.md`, `sync_report.md`). Artifact names are the contract
between steps — rename only with the descriptor.

## 8. HITL gates

A `HITL_GATE` step:

```yaml
- step_id: qa-plan-review
  type: HITL_GATE
  gate:
    gate_id: gate-1-test-plan            # unique within the flow; "escalation" is reserved
    title: "QA review: manual test cases"
    review_instructions: >
      What the reviewer should check, and what AMEND vs REJECT should mean here.
    reviewed_artifacts: [test_plan.md, scope_manifest.md]
```

Opening the gate builds a *review package* — title, instructions, and the
latest version of each `reviewed_artifacts` entry — persists a pending
`HitlReview`, and parks the execution (`AWAITING_HITL`). No pipeline work
happens until a decision.

### Decisions

Via the web UI (`/reviews`) or the API:

```
GET  /api/hitl/reviews?status=PENDING          # inbox (status=ALL for history)
GET  /api/hitl/reviews/{id}                    # full review package
POST /api/hitl/reviews/{id}/decision
     {"decision": "APPROVE" | "AMEND" | "REJECT",
      "comments": "...",
      "amendedArtifacts": {"test_plan.md": "<full new content>"}}   # AMEND only
```

The reviewer is **not** a request field: it comes from the authenticated principal
(HTTP Basic on `/api/**`, the console session in the UI), so a client cannot decide
a gate under someone else's name. With `FACTORY_AUTH_ENABLED=false` every decision
is recorded as `anonymous`.

| Decision | Effect |
|---|---|
| `APPROVE` | execution resumes at the next step |
| `AMEND` | each changed artifact is saved as a **new version** attributed `hitl:<reviewer>` (unchanged submissions are dropped — no no-op versions), then the execution resumes; downstream steps read the amended versions |
| `REJECT` | execution transitions to `REJECTED` (terminal) |

Guards you get for free: decisions are attributed and audited
(`HITL_DECIDED`); stale/duplicate decisions are refused (the execution must
still be parked at that review's step; optimistic locking prevents two
concurrent decisions from both committing).

### Amendment validators

Reviewer edits are input at a trust boundary. Register an
[`ArtifactAmendmentValidator`](../factory-core/src/main/java/org/folio/factory/core/hitl/ArtifactAmendmentValidator.java)
bean to reject malformed amendments **at decision time** (HTTP 4xx to the
reviewer) rather than letting the next worker crash on them:

```java
public interface ArtifactAmendmentValidator {
    void validate(String artifactName, String content);   // throw IllegalArgumentException to refuse
}
```

Every validator bean sees every amendment across all flows, so **self-scope
by artifact name** and return immediately for names you don't own (Test Factory's
[`TestFactoryArtifactAmendmentValidator`](../factory-flow-test-factory/src/main/java/org/folio/factory/testfactory/quality/TestFactoryArtifactAmendmentValidator.java)
is the pattern: it checks frontmatter structure for `test_plan.md` and
`test_scripts.md` only).

### Escalation reviews

When a step exhausts its retry budget (or a sub-flow terminates abnormally),
the platform opens a review with the reserved gate id `escalation` in the same
inbox: the error, the attempt count, and the step's input artifacts.
APPROVE = reset that step's retry budget and run it again (after you fixed
the cause); REJECT = cancel the execution. You cannot name a gate
`escalation` in a descriptor — startup fails.

Design guidance: place a gate **before every external side effect** and after
every artifact a human must stand behind (that's the governance model — the
platform has no autonomous merge/deploy authority), and write
`review_instructions` for the reviewer you actually expect (they render in
the UI as the checklist).

## 9. Sub-flows: composing flows

A flow can invoke any other registered flow as a child execution:

```yaml
- step_id: delegate
  type: SUB_FLOW
  sub_flow:
    flow_id: fake-child
    input_mapping:                # parent artifact → child artifact (copied at invoke)
      parent_doc.md: child_input.md
    output_mapping:               # child artifact → parent artifact (copied on completion)
      child_output.md: collected.md
```

Semantics:

- The child is a full `PipelineExecution` of the referenced flow — own step
  cursor, own retries, own HITL gates, own audit trail — linked to the parent
  (`parentExecutionId`), with the parent's trigger payload.
- The parent parks in `AWAITING_SUBFLOW` for the child's entire lifecycle,
  *including the child's human gates*.
- On child completion, `output_mapping` artifacts are copied up (attributed
  `subflow:<child id>`) and the parent resumes. A child that terminates
  without completing (rejected, cancelled, escalated-and-rejected) escalates
  the parent into the review inbox; approving that escalation **re-invokes
  the sub-flow**.
- Referencing an unregistered `flow_id` fails startup. Recovery is built in:
  a poller-driven reconciler re-delivers lost child-completion hand-offs
  after a crash.

This is how large flows should be built: compose small registered flows
rather than writing 20-step chains. (It exists precisely so the planned Flow E
can orchestrate Flows A and D; your flows can use it the same way. Note the
descriptor-level mechanism supports one child per SUB_FLOW step — fan-out of
children is not a descriptor feature today.)

Integration reference:
[`SubFlowIntegrationTest`](../factory-core/src/test/java/org/folio/factory/core/engine/SubFlowIntegrationTest.java)
with the `fake-parent`/`fake-child` descriptors under
`factory-core/src/test/resources/flows/`.

## 10. Quality controls

[`StepPostProcessor`](../factory-core/src/main/java/org/folio/factory/core/engine/StepPostProcessor.java)
beans run over every AGENT step's outputs **before** they are persisted;
throwing `AgentExecutionException` fails the step (retry → escalate). This is
the framework's static-analysis gate for generated content.

```java
public interface StepPostProcessor {
    void process(FlowDescriptor flow, StepDescriptor step, Map<String, String> outputs)
            throws AgentExecutionException;
}
```

Two things to know:

- **Every post-processor bean runs for every flow's every AGENT step.**
  Framework-wide checks belong in `factory-agents` — like
  [`SecretScanPostProcessor`](../factory-agents/src/main/java/org/folio/factory/agents/quality/SecretScanPostProcessor.java),
  which rejects credential-shaped strings (AWS/GitHub/Slack/Anthropic/OpenAI
  key patterns, private-key blocks) in *any* output of *any* flow.
- **Flow-specific checks must self-scope.** Test Factory's
  [`KarateSanityPostProcessor`](../factory-flow-test-factory/src/main/java/org/folio/factory/testfactory/quality/KarateSanityPostProcessor.java)
  is the pattern: it returns immediately unless the outputs contain
  `test_scripts.md` with `framework: karate`, then validates structure
  (`.feature` extension, `Feature:`/`Scenario` present). Scope by artifact
  name and/or step `config`, never by flow id string-matching if you can help
  it (artifact names are the real contract).

Use post-processors for what must *never* pass (secrets, structurally invalid
generated code, policy violations); use HITL gates for what a human must
*judge*. The two compose: the post-processor keeps garbage from ever reaching
the reviewer.

## 11. Connectors

Connectors are the shared clients for external systems. Today:
[`JiraConnector`](../factory-connectors/src/main/java/org/folio/factory/connectors/jira/JiraConnector.java)
(get issue, comment, transition),
[`GitHubConnector`](../factory-connectors/src/main/java/org/folio/factory/connectors/github/GitHubConnector.java)
(create branch, commit files, open PR),
[`TestRailConnector`](../factory-connectors/src/main/java/org/folio/factory/connectors/testrail/TestRailConnector.java)
(add cases, runs, results).

### The configured/unconfigured pattern

Each connector binds at startup to either its real REST implementation (when
its properties are present) or a fallback whose every method throws
`ConnectorNotConfiguredException` naming the exact env vars to set. Both
implement `ConnectorHealth`, which feeds `/api/status` and the readiness
endpoint. Workers therefore never null-check connectors — they decide *how a
missing integration degrades*:

- **Data-critical** (the flow cannot proceed without it): don't catch the
  exception. The step fails, retries, escalates — a human sees "Jira connector
  not configured: set FACTORY_CONNECTORS_JIRA_…" in the review inbox.
  Example: Test Factory's triage when the trigger has no inline issue.
- **Degradable** (the flow can complete without the integration — but not
  without knowing): catch `ConnectorNotConfiguredException`, record it with
  `WorkerAuditSink.connectorSkipped` plus a `skipped` line in the step's report
  artifact, and continue. That is the zero-credentials path. Catch
  `RestClientException` **separately**: a configured-but-failing system is a real
  failure, so record `connectorFailed` and then throw, letting the step retry and
  escalate — see the [failure semantics](#failure-semantics--design-for-them)
  note on replay-safe side effects. Example: every sync in Test Factory's
  finalizer, §3.5's publisher.

### Adding a new connector

Follow the existing structure in `factory-connectors` (one package per
system):

1. **Interface** — narrow, task-level methods (`createPage(space, title,
   body)`), not a generic HTTP wrapper. This is what workers compile against
   and what tests fake.
2. **Properties** — a `@ConfigurationProperties(prefix =
   "factory.connectors.<name>")` record with an `isConfigured()` method
   checking the minimum viable credential set (see
   [`JiraProperties`](../factory-connectors/src/main/java/org/folio/factory/connectors/jira/JiraProperties.java)).
3. **REST implementation** — build from the injected `RestClient.Builder`
   (clone it), so the platform's HTTP timeouts
   (`FACTORY_HTTP_CONNECT_TIMEOUT` / `_READ_TIMEOUT`) apply. Implement
   `ConnectorHealth`.
4. **Unconfigured fallback** — add a nested class to
   [`UnconfiguredConnectors`](../factory-connectors/src/main/java/org/folio/factory/connectors/UnconfiguredConnectors.java);
   every method throws `ConnectorNotConfiguredException` with the full list
   of env vars to set.
5. **Bean wiring** — add the configured-or-fallback `@Bean` pair to
   [`ConnectorsConfiguration`](../factory-connectors/src/main/java/org/folio/factory/connectors/ConnectorsConfiguration.java)
   (including the explicit `ConnectorHealth` re-exposure — the comment there
   explains why).
6. **Configuration mapping** — add `factory.connectors.<name>.*` entries with
   explicit `${FACTORY_CONNECTORS_<NAME>_*:}` placeholders to
   `factory-app/src/main/resources/application.yaml`, and document them in the
   README table.
7. **Tests** — WireMock the REST API (see the connector stubs in
   [`TestFactoryEndToEndTest`](../factory-app/src/test/java/org/folio/factory/app/TestFactoryEndToEndTest.java))
   and assert the fallback's error message names every required variable.

Never call an external system from a worker with a hand-rolled HTTP client:
the connector layer is what gives you timeouts, health reporting, graceful
degradation, and a single place to mock.

## 12. Per-flow configuration

Flow modules own their settings via a `@ConfigurationProperties` record:

```java
@ConfigurationProperties(prefix = "factory.relnotes")
public record ReleaseNotesProperties(String targetRepo, String baseBranch) {
    public ReleaseNotesProperties {
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
    }
}
```

Enable it on your flow's `@Configuration`
(`@EnableConfigurationProperties(ReleaseNotesProperties.class)`), inject it
into workers, and map the env vars in
`factory-app/src/main/resources/application.yaml`:

```yaml
factory:
  relnotes:
    target-repo: ${FACTORY_RELNOTES_TARGET_REPO:}
    base-branch: ${FACTORY_RELNOTES_BASE_BRANCH:main}
```

Two house rules, both load-bearing:

- **Explicit `${ENV_VAR:}` placeholders, always.** Relaxed binding alone does
  not map `FACTORY_RELNOTES_TARGET_REPO` onto a kebab-case property — the
  placeholder is what makes the env var work. Every existing property follows
  this; so must yours.
- **Empty default = feature off, not startup failure.** Follow the
  `isConfigured()` pattern (see `TestFactoryProperties.Execution`) and degrade the
  behaviour (advisory mode, skipped sync) when unset. Document every new
  variable in the README configuration table.

Values that vary *per flow definition* (not per deployment) belong in the
descriptor's step `config`, not in properties.

## 13. Testing your flow

Docker is required (`mvn verify` uses Testcontainers PostgreSQL). No LLM API
keys are ever needed in tests. The division of labour:

- **Engine semantics are already covered** — retry → escalation, poller crash
  recovery, HITL decision rules, sub-flow parent/child, claim limits live in
  `factory-core`'s integration tests
  (`ExecutionEngineIntegrationTest`, `ExecutionPollerIntegrationTest`,
  `HitlDecisionIntegrationTest`, `SubFlowIntegrationTest`, …) against fake
  workers ([`FakeEchoWorker`](../factory-core/src/test/java/org/folio/factory/core/FakeEchoWorker.java))
  and fake descriptors (`factory-core/src/test/resources/flows/`). **Do not
  re-test the engine through your flow**, and never mock the engine.
- **Your workers get unit tests** in your flow module: fabricate an
  `AgentContext` (it's a record), call `execute`, assert on the returned
  artifacts — including the failure paths (missing input, unparseable
  upstream artifact, unconfigured connector). For LLM workers, script the
  model (below) or fake the DTO-producing call.
- **Your flow gets one end-to-end test** in `factory-app`, modelled on
  [`TestFactoryEndToEndTest`](../factory-app/src/test/java/org/folio/factory/app/TestFactoryEndToEndTest.java)
  / [`TestFactoryZeroCredentialsTest`](../factory-app/src/test/java/org/folio/factory/app/TestFactoryZeroCredentialsTest.java).

The e2e recipe (all pieces visible in `TestFactoryEndToEndTest`):

1. `@SpringBootTest(webEnvironment = RANDOM_PORT, properties =
   {"spring.ai.model.chat=none", "factory.engine.poll-interval-ms=250"})` —
   disable the real LLM auto-configuration, speed up the poller.
2. `@Import(StubLlmConfiguration.class)` — a scripted `ChatModel`
   ([`StubLlmConfiguration`](../factory-app/src/test/java/org/folio/factory/app/StubLlmConfiguration.java))
   that recognises which worker is calling by a distinctive phrase in its
   system prompt and returns canned JSON for `callForEntity` to parse.
   Extend it (or write your flow's own) with a branch per LLM worker — this is
   why §6 says keep a distinctive role phrase in every system prompt.
3. Testcontainers PostgreSQL via `@Container @ServiceConnection`.
4. WireMock servers per connector, wired through `@DynamicPropertySource`
   (`factory.connectors.<name>.base-url` → the WireMock URL), plus your
   flow's properties.
5. Drive the flow through the real HTTP API: `POST /api/triggers/manual`,
   `await()` (Awaitility) until the execution parks `AWAITING_HITL`, decide
   via `POST /api/hitl/reviews/{id}/decision` — **exercise an AMEND**, not
   just approvals, and assert the amended content reached the downstream
   worker — then assert terminal status, artifact versions, audit events, and
   the WireMock'd external calls. Keep authentication **on**
   (`factory.auth.enabled=true`, `factory.auth.users=qa-lead:<password>`) and set
   `setBasicAuth` on the `RestClient`, as `TestFactoryEndToEndTest` does: the
   reviewer recorded on an amendment comes from the authenticated principal, and
   an e2e test with auth disabled cannot assert that.
6. Add a zero-credentials variant: no connector properties, assert the flow
   still completes with `CONNECTOR_SKIPPED` audit events. This keeps the
   advisory-mode promise true for your flow.

Descriptor changes are validated by startup itself (every test that boots the
app re-validates all descriptors), so a YAML typo fails fast across the suite.

```bash
mvn verify                                              # everything
mvn -pl factory-app test -Dtest=TestFactoryEndToEndTest       # one e2e class
mvn -pl factory-core test -Dtest=SubFlowIntegrationTest#method  # one method
```

## 14. Running, observing, debugging

| Surface | URL | What it's for |
|---|---|---|
| Review inbox (UI) | `/reviews`, `/reviews/{id}` | work gates: read package, approve/amend/reject |
| Executions (UI) | `/executions`, `/executions/{id}` | step cursor, artifact versions, full audit timeline |
| Manual trigger | `POST /api/triggers/manual` | start a flow by id |
| Webhooks | `POST /api/webhooks/jira`, `/api/webhooks/github` | external triggers (`X-Factory-Webhook-Token` shared secret; GitHub also `X-Hub-Signature-256`) |
| Reviews API | `/api/hitl/reviews…` | scripted gate decisions (§8) |
| Executions API | `GET /api/executions`, `/api/executions/{id}` | programmatic status/artifacts/audit |
| Connector status | `GET /api/status` | which integrations are live vs unconfigured |
| Health / metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` | probes, per-worker step timings/outcomes, connector outcomes |

Everything above except the webhooks and the health probes is authenticated:
console pages by form login at `/login` (Basic also accepted), `/api/**` by HTTP
Basic, `/actuator/**` by Basic unless `FACTORY_AUTH_METRICS_PUBLIC=true` opens
the metrics endpoints. Accounts come from `FACTORY_AUTH_USERS`; unset, a
generated `admin` password is logged at startup. `/actuator/health` shows its
`db`/`connectors` detail only to an authenticated caller, so read connector state
from `/status` or `GET /api/status`.

Debugging aids: every log line during an execution carries `executionId` (and
`stepId` inside a step) via MDC — set `FACTORY_LOG_FORMAT=ecs` for JSON logs
with those fields; the audit timeline on `/executions/{id}` is usually the
fastest way to see exactly where a run stopped and why; per-step metrics land
in `/actuator/prometheus`. Operational procedures (stuck runs, draining,
retention, alerts) are in the [runbook](runbook.md).

Common startup failures (all deliberate — the platform refuses to boot with a
broken plugin):

| Message contains | Cause |
|---|---|
| `Cannot parse flow descriptor … Unrecognized property` | typo/unknown field in YAML (strict parsing, snake_case) |
| `missing required field 'id'/'name'/'version'` | incomplete descriptor header |
| `duplicate step_id` / `Duplicate flow id` | id collision in a chain / between modules |
| `references unknown agent worker '…'` | `worker_id` without a matching bean — bean not wired, module not on `factory-app`'s classpath, or id typo |
| `Duplicate agent worker id` | two beans return the same `id()` |
| `references unregistered sub-flow` | `sub_flow.flow_id` names a flow that isn't on the classpath |
| `gate id 'escalation' is reserved` | rename your gate |
| `flow must contain at least one AGENT step` | gates/sub-flows only — add a worker step |

At runtime: `Cannot load prompt template classpath:prompts/…` (prompt
file/worker-id mismatch), `Step '…' requires artifact '…' which does not
exist` (an `inputs` entry nothing upstream produces — check the chain's
outputs), `did not produce declared output artifact` (worker/descriptor
contract drift).

## 15. Adopting the Factory on a new project

The platform is FOLIO-flavoured only at its edges. To run it for a different
organisation/project:

1. **Keep** `factory-core`, `factory-agents`, `factory-connectors`,
   `factory-app` — they contain no flow-specific logic.
2. **Choose flows**: drop the `factory-flow-test-factory` dependency from
   `factory-app/pom.xml` (and the module from the root pom) if the Test Factory flow is not
   relevant, and add your own flow modules per §3. Test Factory's env vars
   (`FACTORY_TEST_FACTORY_*`) simply become unused.
3. **Swap the LLM provider if needed.** Workers only see Spring AI's
   `ChatClient`. Replace `spring-ai-starter-model-anthropic` in
   `factory-app/pom.xml` with another Spring AI model starter and the
   `spring.ai.anthropic.*` block in `application.yaml` with the equivalent
   for your provider (keep a `chat.options.model` mapping so the
   `FACTORY_LLM_MODEL` convention survives). Nothing in any worker changes.
4. **Wire your external systems**: reuse the Jira/GitHub/TestRail connectors
   if applicable; add others per §11. New webhook sources get an adapter
   endpoint per §4.
5. **Operational baseline**: set `FACTORY_WEBHOOKS_SHARED_SECRET` (webhooks
   are unauthenticated without it — the app warns at startup), review the
   cost limits (`FACTORY_LIMITS_*`) and retention (`FACTORY_RETENTION_*`)
   defaults, and note that the HITL UI/API itself carries no authentication
   in Milestone 1 — deploy it behind your ingress/SSO. For multiple app
   instances, set `FACTORY_ENGINE_RECLAIM_RUNNING_ON_STARTUP=false` (see
   `application.yaml` for why).
6. **Database**: PostgreSQL + the bundled Flyway migrations
   (`factory-app/src/main/resources/db/migration/`) are the only persistence
   requirements. Backup/restore procedure: [backup-dr.md](backup-dr.md).

What you should **not** change: the invariants in §1. If your plan requires
mutable artifacts, un-audited actions, autonomous external side effects, or
flow logic inside `factory-core`, the plan — not the platform — needs to
change.

## 16. Design rules checklist

Before opening a PR with a new or changed flow:

- [ ] No `factory-core` changes. If your flow "needed" one, redesign the flow
      (or propose the framework feature separately, flow-agnostically).
- [ ] Descriptor validates: `mvn -pl factory-app test` boots the app and
      fails on any descriptor error.
- [ ] Every step's `inputs` are produced by an earlier step's `outputs` (or
      `$trigger`); workers read nothing else and return every declared output.
- [ ] Workers are stateless; no in-memory hand-off between steps; no
      cross-execution caches.
- [ ] Every external side effect sits **after** a HITL gate, is guarded by its
      own `WorkerAuditSink` action key — content-addressed on the artifact it
      transmits, so a retry skips but an amendment re-syncs — degrades to
      skipped-and-audited when its connector is unconfigured, and throws when a
      configured connector fails.
- [ ] Workers audit through `WorkerAuditSink`, never `AuditLog`.
- [ ] LLM output is validated (structured records, sanity checks, post-
      processors) — a worker that trusts the model blindly ships the model's
      mistakes downstream.
- [ ] Prompts instruct synthetic test data only; secret scanning stays on.
- [ ] Reviewer-amendable artifacts have an amendment validator; review
      instructions actually tell the reviewer what to check.
- [ ] Unit tests for workers (including failure paths); one e2e test per flow
      with scripted LLM + WireMock'd connectors, covering an AMEND and the
      zero-credentials path.
- [ ] New env vars use explicit `${ENV_VAR:}` placeholders and are documented
      in the README table.
