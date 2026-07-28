package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Final side-effect step: TestRail sync, repository commit + PR, Jira update.
 *
 * <p>An action whose connector is not configured is skipped and the flow completes —
 * that is the advisory, zero-credentials path. An action that was attempted and
 * <em>failed</em> is different: the step throws, so the engine retries it under the
 * flow's retry policy and finally escalates it into the human review inbox instead
 * of completing a run whose external state was never written.</p>
 *
 * <p>Retrying non-idempotent side effects is only safe because each one is recorded
 * in the audit ledger under a stable action key as it completes, and skipped on the
 * next attempt — the ledger, not the sync report, is the durable record, because a
 * crash midway leaves the step without any artifact write at all. Each key covers a
 * single irreversible call (one TestRail case, the run, the results), because a key
 * spanning several of them cannot be written until the last one succeeds: a failure
 * in between would leave the earlier ones unrecorded and the retry would repeat them.</p>
 *
 * <p>Each key is content-addressed on the artifact its connector consumes — the script
 * bundle for the GitHub push, the plan for the TestRail cases and run, the results for
 * the Jira comment. An ordinary retry reads the same artifact versions, so it still
 * skips everything that completed; an amendment made at the escalation gate this step
 * escalates into is a new artifact version, so it produces new keys and re-syncs the
 * connector that consumes it — and only that one. Without this a corrected artifact
 * would be silently dropped: every key was already recorded, so the retry would skip
 * every connector and still report the sync as done. The Jira transition carries no
 * fingerprint: it moves the issue, which is not a function of any artifact, so an
 * amendment must not replay it.</p>
 */
public class TestFactoryFinalizerWorker implements AgentWorker {

    public static final String ID = "test-factory-finalizer";

    static final String GITHUB_PR = "github.pr";
    static final String TESTRAIL_CASES = "testrail.cases";
    static final String TESTRAIL_CASE_PREFIX = "testrail.case:";
    static final String TESTRAIL_RUN = "testrail.run";
    static final String TESTRAIL_RESULTS = "testrail.results";
    static final String JIRA_COMMENT = "jira.comment";
    static final String JIRA_TRANSITION = "jira.transition";

    private static final String ALREADY_DONE = "completed on an earlier attempt";

    private static final Logger log = LoggerFactory.getLogger(TestFactoryFinalizerWorker.class);

    private final JiraConnector jira;
    private final GitHubConnector gitHub;
    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;
    private final ScriptBundleCodec bundleCodec;
    private final TestFactoryProperties properties;
    private final WorkerAuditSink auditSink;
    private final EngineMetrics engineMetrics;

    public TestFactoryFinalizerWorker(JiraConnector jira, GitHubConnector gitHub,
                                      TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                      ScriptBundleCodec bundleCodec, TestFactoryProperties properties,
                                      WorkerAuditSink auditSink, EngineMetrics engineMetrics) {
        this.jira = jira;
        this.gitHub = gitHub;
        this.testRail = testRail;
        this.frontmatterCodec = frontmatterCodec;
        this.bundleCodec = bundleCodec;
        this.properties = properties;
        this.auditSink = auditSink;
        this.engineMetrics = engineMetrics;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        ArtifactContent planArtifact = context.requireInput("test_plan.md");
        ArtifactContent resultsArtifact = context.requireInput("test_results.md");
        ArtifactContent scriptsArtifact = context.requireInput("test_scripts.md");
        Frontmatter plan = frontmatterCodec.parse(planArtifact.content());
        Frontmatter results = frontmatterCodec.parse(resultsArtifact.content());
        ScriptBundle bundle = bundleCodec.parse(scriptsArtifact.content());
        String issueKey = plan.metadata().path("issue_key").asString("");
        if (issueKey.isBlank() && context.triggerPayload() != null) {
            issueKey = context.triggerPayload().path("issueKey").asString("");
        }
        if (issueKey.isBlank()) {
            // Fail fast before any external side effect: a blank key would produce
            // garbage branch names, PR titles and Jira comment targets.
            throw new AgentExecutionException(
                    "Cannot finalize: no issue key in test_plan.md frontmatter or trigger payload");
        }

        String resultsFingerprint = fingerprint(resultsArtifact);
        List<Map<String, String>> actions = new ArrayList<>();
        String prUrl = syncGitHub(context, actions, bundle, issueKey, fingerprint(scriptsArtifact));
        syncTestRail(context, actions, plan, results, issueKey, fingerprint(planArtifact), resultsFingerprint);
        syncJira(context, actions, results, issueKey, prUrl, resultsFingerprint);
        requireNoFailures(actions);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", issueKey);
        metadata.put("actions", actions);
        StringBuilder body = new StringBuilder("# Sync Report — ").append(issueKey).append("\n\n");
        actions.forEach(action -> body.append("- **").append(action.get("connector")).append("** ")
                .append(action.get("action")).append(": ").append(action.get("status"))
                .append(action.get("detail").isBlank() ? "" : " — " + action.get("detail")).append("\n"));
        return AgentResult.of("sync_report.md", frontmatterCodec.render(metadata, body.toString()));
    }

    private void requireNoFailures(List<Map<String, String>> actions) {
        List<String> failures = actions.stream()
                .filter(action -> "failed".equals(action.get("status")))
                .map(action -> action.get("connector") + " (" + action.get("action") + "): "
                        + action.get("detail"))
                .toList();
        if (!failures.isEmpty()) {
            throw new AgentExecutionException("External sync failed for "
                    + failures.size() + " action(s): " + String.join("; ", failures));
        }
    }

    private String syncGitHub(AgentContext context, List<Map<String, String>> actions,
                              ScriptBundle bundle, String issueKey, String scriptsFingerprint) {
        String prKey = GITHUB_PR + scriptsFingerprint;
        if (properties.targetRepo() == null || properties.targetRepo().isBlank()) {
            record(context, actions, "github", prKey, "commit scripts", "skipped",
                    "no target repository configured (FACTORY_TEST_FACTORY_TARGET_REPO)");
            return null;
        }
        Optional<String> completed = auditSink.completedDetail(context.executionId(), "github", prKey);
        if (completed.isPresent()) {
            report(actions, "github", "branch + commit + PR", "done", ALREADY_DONE);
            // The recorded detail is the pull request URL, except on the "already
            // exists" path where GitHub never gave us one and it holds prose instead.
            return completed.filter(detail -> detail.startsWith("http")).orElse(null);
        }
        String branch = "test-factory/" + issueKey;
        try {
            try {
                gitHub.createBranch(properties.targetRepo(), properties.baseBranch(), branch);
            } catch (HttpClientErrorException e) {
                // 422 "Reference already exists": a previous attempt created the
                // branch; committing onto it is exactly what we want.
                if (e.getStatusCode().value() != 422) {
                    throw e;
                }
                log.info("Branch {} already exists in {}; reusing it", branch, properties.targetRepo());
            }
            Map<String, String> files = new HashMap<>();
            bundle.files().forEach(f -> files.put(f.path(), f.content()));
            gitHub.commitFiles(properties.targetRepo(), branch, files,
                    issueKey + ": generated test scripts (AI Test Factory)");
            String prUrl;
            try {
                prUrl = gitHub.createPullRequest(properties.targetRepo(), branch, properties.baseBranch(),
                        issueKey + ": AI-generated test scripts",
                        "Generated by the AI SDLC Factory Test Factory flow for " + issueKey
                                + ".\n\nLabels: ai-generated");
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 422) {
                    throw e;
                }
                // A PR for this branch already exists (or there is nothing new to
                // propose) — not a failure for an idempotent re-run. The commit above
                // has already carried an amended bundle onto that same pull request.
                record(context, actions, "github", prKey, "branch + commit + PR", "done",
                        "pull request already exists for " + branch);
                return null;
            }
            record(context, actions, "github", prKey, "branch + commit + PR", "done", prUrl);
            return prUrl;
        } catch (ConnectorNotConfiguredException e) {
            record(context, actions, "github", prKey, "commit scripts", "skipped", e.getMessage());
            return null;
        } catch (RestClientException e) {
            record(context, actions, "github", prKey, "commit scripts", "failed", e.getMessage());
            return null;
        }
    }

    private void syncTestRail(AgentContext context, List<Map<String, String>> actions,
                              Frontmatter plan, Frontmatter results, String issueKey,
                              String planFingerprint, String resultsFingerprint) {
        boolean executed = "EXECUTED".equals(results.metadata().path("mode").asString(""));
        // Advisory results are never sent to TestRail, so amending them must not
        // re-open this block just to report work it would not redo.
        String casesKey = TESTRAIL_CASES + planFingerprint + (executed ? resultsFingerprint : "");
        if (properties.testrailSectionId() == null) {
            record(context, actions, "testrail", casesKey, "sync cases", "skipped",
                    "no TestRail section configured (FACTORY_TEST_FACTORY_TESTRAIL_SECTION_ID)");
            return;
        }
        if (auditSink.completedDetail(context.executionId(), "testrail", casesKey).isPresent()) {
            report(actions, "testrail", "cases + run", "done", ALREADY_DONE);
            return;
        }
        try {
            Map<String, Long> testRailCaseIds = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode testCase : plan.metadata().path("cases")) {
                String planCaseId = testCase.path("id").asString("");
                testRailCaseIds.put(planCaseId,
                        addCaseOnce(context, planCaseId, index++, testCase, issueKey, planFingerprint));
            }
            long runId = addRunOnce(context, issueKey, new ArrayList<>(testRailCaseIds.values()), planFingerprint);
            if (executed) {
                addResultsOnce(context, runId, results, testRailCaseIds,
                        TESTRAIL_RESULTS + planFingerprint + resultsFingerprint);
            }
            record(context, actions, "testrail", casesKey,
                    "cases + run" + (executed ? " + results" : ""),
                    "done", testRailCaseIds.size() + " case(s), run " + runId);
        } catch (ConnectorNotConfiguredException e) {
            record(context, actions, "testrail", casesKey, "sync cases", "skipped", e.getMessage());
        } catch (RestClientException e) {
            record(context, actions, "testrail", casesKey, "sync cases", "failed", e.getMessage());
        }
    }

    /**
     * Case creation is the TestRail call with no natural key, so its ledger entry
     * carries the created id: a re-run has to recover it to build the run rather than
     * create the case a second time. The plan's own case id keys the entry; a plan
     * that omits it falls back to the case's position, which is just as stable
     * because the plan artifact a re-run reads is immutable. An amended plan is a
     * different artifact, so its cases key differently and the whole set is created
     * anew — the created cases carry the superseded plan's wording and cannot be
     * edited into the amended one from here.
     */
    private long addCaseOnce(AgentContext context, String planCaseId, int index,
                             JsonNode testCase, String issueKey, String planFingerprint) {
        String caseRef = planCaseId.isBlank() ? "#" + index : planCaseId;
        String actionKey = TESTRAIL_CASE_PREFIX + caseRef + planFingerprint;
        Optional<String> completed = auditSink.completedDetail(context.executionId(), "testrail", actionKey);
        if (completed.isPresent()) {
            return recordedId(completed.get(), actionKey);
        }
        long caseId = testRail.addCase(properties.testrailSectionId(),
                "[" + issueKey + "] " + testCase.path("title").asString(""),
                String.join("\n", toStrings(testCase.path("steps"))),
                testCase.path("expected").asString(""));
        auditSink.connectorAction(context.executionId(), context.stepId(), ID,
                "testrail", actionKey, "case " + caseRef, String.valueOf(caseId));
        return caseId;
    }

    private long addRunOnce(AgentContext context, String issueKey, List<Long> caseIds,
                            String planFingerprint) {
        String actionKey = TESTRAIL_RUN + planFingerprint;
        Optional<String> completed = auditSink.completedDetail(context.executionId(), "testrail", actionKey);
        if (completed.isPresent()) {
            return recordedId(completed.get(), actionKey);
        }
        long runId = testRail.addRun(issueKey + " — AI Test Factory", caseIds);
        auditSink.connectorAction(context.executionId(), context.stepId(), ID,
                "testrail", actionKey, "run", String.valueOf(runId));
        return runId;
    }

    private void addResultsOnce(AgentContext context, long runId, Frontmatter results,
                                Map<String, Long> testRailCaseIds, String actionKey) {
        if (auditSink.completedDetail(context.executionId(), "testrail", actionKey).isPresent()) {
            return;
        }
        Map<Long, Boolean> outcome = new LinkedHashMap<>();
        for (JsonNode caseResult : results.metadata().path("case_results")) {
            Long testRailId = testRailCaseIds.get(caseResult.path("case_id").asString(""));
            if (testRailId != null) {
                outcome.put(testRailId, "PASSED".equals(caseResult.path("status").asString("")));
            }
        }
        testRail.addResults(runId, outcome, "Automated by AI Test Factory");
        auditSink.connectorAction(context.executionId(), context.stepId(), ID,
                "testrail", actionKey, "results", "run " + runId);
    }

    private static long recordedId(String detail, String actionKey) {
        try {
            return Long.parseLong(detail);
        } catch (NumberFormatException e) {
            throw new AgentExecutionException("Ledger entry for '" + actionKey
                    + "' carries no TestRail id ('" + detail + "'), so the outstanding"
                    + " TestRail work cannot be resumed without duplicating it", e);
        }
    }

    private void syncJira(AgentContext context, List<Map<String, String>> actions,
                          Frontmatter results, String issueKey, String prUrl, String resultsFingerprint) {
        String mode = results.metadata().path("mode").asString("");
        String commentKey = JIRA_COMMENT + resultsFingerprint;
        if (auditSink.completedDetail(context.executionId(), "jira", commentKey).isPresent()) {
            report(actions, "jira", "comment", "done", ALREADY_DONE);
        } else {
            try {
                jira.addComment(issueKey, "AI Test Factory completed for " + issueKey
                        + ".\nResults mode: " + mode
                        + (prUrl == null ? "" : "\nTest scripts PR: " + prUrl));
                record(context, actions, "jira", commentKey, "comment", "done", "");
            } catch (ConnectorNotConfiguredException e) {
                record(context, actions, "jira", commentKey, "comment", "skipped", e.getMessage());
                return;
            } catch (RestClientException e) {
                record(context, actions, "jira", commentKey, "comment", "failed", e.getMessage());
                return;
            }
        }
        if (properties.jiraTransition() == null || properties.jiraTransition().isBlank()) {
            return;
        }
        String label = "transition '" + properties.jiraTransition() + "'";
        if (auditSink.completedDetail(context.executionId(), "jira", JIRA_TRANSITION).isPresent()) {
            report(actions, "jira", label, "done", ALREADY_DONE);
            return;
        }
        try {
            jira.transitionIssue(issueKey, properties.jiraTransition());
            record(context, actions, "jira", JIRA_TRANSITION, label, "done", "");
        } catch (ConnectorNotConfiguredException | IllegalArgumentException e) {
            record(context, actions, "jira", JIRA_TRANSITION, label, "skipped", e.getMessage());
        } catch (RestClientException e) {
            record(context, actions, "jira", JIRA_TRANSITION, label, "failed", e.getMessage());
        }
    }

    private static String fingerprint(ArtifactContent artifact) {
        return "@" + ArtifactStore.sha256(artifact.content()).substring(0, 12);
    }

    private List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString("")));
        return values;
    }

    /**
     * Reports the outcome and writes it to the ledger as it happens — a later action
     * failing must not erase what an earlier one already did.
     */
    private void record(AgentContext context, List<Map<String, String>> actions, String connector,
                        String actionKey, String label, String status, String detail) {
        String safeDetail = detail == null ? "" : detail;
        report(actions, connector, label, status, safeDetail);
        switch (status) {
            case "skipped" -> {
                auditSink.connectorSkipped(context.executionId(), context.stepId(), ID,
                        connector, actionKey, label, safeDetail);
                log.info("Connector {} skipped for execution {}: {}", connector, context.executionId(), safeDetail);
            }
            case "failed" -> {
                auditSink.connectorFailed(context.executionId(), context.stepId(), ID,
                        connector, actionKey, label, safeDetail);
                log.warn("Connector {} sync FAILED for execution {} ({}): {}",
                        connector, context.executionId(), label, safeDetail);
            }
            default -> auditSink.connectorAction(context.executionId(), context.stepId(), ID,
                    connector, actionKey, label, safeDetail);
        }
        engineMetrics.connectorOutcome(connector, status);
    }

    /** Sync-report line only: used where the side effect was not performed on this attempt. */
    private void report(List<Map<String, String>> actions, String connector, String action,
                        String status, String detail) {
        actions.add(Map.of("connector", connector, "action", action, "status", status, "detail", detail));
    }
}
