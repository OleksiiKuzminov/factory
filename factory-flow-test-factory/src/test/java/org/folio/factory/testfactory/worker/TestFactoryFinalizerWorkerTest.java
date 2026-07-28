package org.folio.factory.testfactory.worker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestFactoryFinalizerWorkerTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final String STEP_ID = "finalize";
    private static final String ISSUE_KEY = "ERM-9";
    private static final String REPO = "o/r";
    private static final String BRANCH = "test-factory/ERM-9";
    private static final String PR_URL = "https://github.com/o/r/pull/9";
    private static final String RUN_NAME = ISSUE_KEY + " — AI Test Factory";
    private static final long SECTION_ID = 55L;

    private final FrontmatterCodec codec = new FrontmatterCodec();
    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(codec);

    @Mock
    private GitHubConnector gitHub;

    @Mock
    private TestRailConnector testRail;

    @Mock
    private JiraConnector jira;

    @Mock
    private WorkerAuditSink auditSink;

    @Test
    void completesSuccessfullyWhenNoConnectorIsConfiguredSoTheAdvisoryPathStillFinishes() {
        doThrow(new ConnectorNotConfiguredException("Jira is not configured (FACTORY_CONNECTORS_JIRA_BASE_URL)"))
                .when(jira).addComment(eq(ISSUE_KEY), anyString());

        AgentResult result = worker(properties(null, null, null)).execute(context());

        assertThat(result.outputs().get("sync_report.md"))
                .contains("**github** commit scripts: skipped")
                .contains("**testrail** sync cases: skipped")
                .contains("**jira** comment: skipped");
        verify(auditSink, times(3)).connectorSkipped(eq(EXECUTION_ID), eq(STEP_ID),
                eq(TestFactoryFinalizerWorker.ID), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void throwsNamingTheFailedConnectorSoTheEngineRetriesInsteadOfCompletingSilently() {
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        when(testRail.addCase(SECTION_ID, "[ERM-9] works", "s", "e")).thenReturn(1L);
        when(testRail.addRun(RUN_NAME, List.of(1L))).thenThrow(new RestClientException("TestRail returned 500"));

        assertThatThrownBy(() -> worker(properties(REPO, SECTION_ID, null)).execute(context()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("testrail")
                .hasMessageContaining("TestRail returned 500");

        // Later connectors still ran, and the GitHub success was written to the ledger
        // as it happened so the retry that follows will not re-open the pull request.
        verify(jira).addComment(eq(ISSUE_KEY), anyString());
        verify(auditSink).connectorAction(eq(EXECUTION_ID), eq(STEP_ID), eq(TestFactoryFinalizerWorker.ID),
                eq("github"), startsWith(TestFactoryFinalizerWorker.GITHUB_PR), eq("branch + commit + PR"),
                eq(PR_URL));
        verify(auditSink).connectorFailed(eq(EXECUTION_ID), eq(STEP_ID), eq(TestFactoryFinalizerWorker.ID),
                eq("testrail"), startsWith(TestFactoryFinalizerWorker.TESTRAIL_CASES), eq("sync cases"), anyString());
    }

    @Test
    void skippedConnectorsAloneDoNotFailTheStep() {
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        doThrow(new ConnectorNotConfiguredException("TestRail is not configured"))
                .when(testRail).addCase(SECTION_ID, "[ERM-9] works", "s", "e");

        AgentResult result = worker(properties(REPO, SECTION_ID, null)).execute(context());

        assertThat(result.outputs().get("sync_report.md"))
                .contains("**testrail** sync cases: skipped")
                .contains("**github** branch + commit + PR: done");
    }

    @Test
    void doesNotRepeatASideEffectTheLedgerAlreadyRecordsAsCompleted() {
        completed("github", TestFactoryFinalizerWorker.GITHUB_PR, PR_URL);
        completed("testrail", TestFactoryFinalizerWorker.TESTRAIL_CASES, "1 case(s), run 77");
        completed("jira", TestFactoryFinalizerWorker.JIRA_COMMENT, "");

        AgentResult result = worker(properties(REPO, SECTION_ID, null)).execute(context());

        verifyNoInteractions(gitHub, testRail, jira);
        assertThat(result.outputs().get("sync_report.md"))
                .contains("**github** branch + commit + PR: done — completed on an earlier attempt")
                .contains("**testrail** cases + run: done — completed on an earlier attempt")
                .contains("**jira** comment: done — completed on an earlier attempt");
    }

    /**
     * The regression this pins: a TestRail case is irreversible, so a failure after
     * some of them were created must not put the whole loop back on the retry's
     * critical path. The ledger here is not a canned answer — it can only report what
     * the worker actually recorded, so a key that is written once per block instead of
     * once per case leaves the second attempt free to create the cases again.
     */
    @Test
    void aRetryAfterTheRunFailedCreatesNoDuplicateTestRailCases() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(REPO, SECTION_ID, null);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        when(testRail.addCase(SECTION_ID, "[ERM-9] first", "s1", "e1")).thenReturn(11L);
        when(testRail.addCase(SECTION_ID, "[ERM-9] second", "s2", "e2")).thenReturn(12L);
        when(testRail.addRun(RUN_NAME, List.of(11L, 12L)))
                .thenThrow(new RestClientException("TestRail returned 500"))
                .thenReturn(77L);

        assertThatThrownBy(() -> worker(properties, ledger).execute(twoCaseContext()))
                .isInstanceOf(AgentExecutionException.class);
        AgentResult retry = worker(properties, ledger).execute(twoCaseContext());

        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] first", "s1", "e1");
        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] second", "s2", "e2");
        verify(gitHub, times(1)).createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString());
        verify(gitHub, times(1)).commitFiles(eq(REPO), eq(BRANCH), anyMap(), anyString());
        verify(jira, times(1)).addComment(eq(ISSUE_KEY), anyString());
        // The one action that had not happened yet is the one the retry performs.
        verify(testRail, times(2)).addRun(RUN_NAME, List.of(11L, 12L));
        assertThat(retry.outputs().get("sync_report.md"))
                .contains("**testrail** cases + run: done — 2 case(s), run 77");
    }

    /**
     * Crash recovery, not retry: an attempt that died between the last TestRail call
     * and the step's own bookkeeping left every irreversible call recorded. The
     * re-run must resume from those ids rather than rebuild the run under new ones.
     */
    @Test
    void aRerunResumesFromTheCaseAndRunIdsTheLedgerRecorded() {
        // Seeded through the real ledger, so the re-run sees exactly what the dead
        // attempt got as far as recording: every irreversible call, and not the
        // block-level summary the step only writes once all of them are done.
        RecordingAuditSink ledger = new RecordingAuditSink();
        String plan = twoCasePlan();
        ledger.seed("testrail", keyed(TestFactoryFinalizerWorker.TESTRAIL_CASE_PREFIX + "TC-01", plan), "11");
        ledger.seed("testrail", keyed(TestFactoryFinalizerWorker.TESTRAIL_CASE_PREFIX + "TC-02", plan), "12");
        ledger.seed("testrail", keyed(TestFactoryFinalizerWorker.TESTRAIL_RUN, plan), "77");

        AgentResult result = worker(properties(null, SECTION_ID, null), ledger)
                .execute(twoCaseContext(executedResults()));

        verify(testRail, never()).addCase(anyLong(), anyString(), anyString(), anyString());
        verify(testRail, never()).addRun(anyString(), anyList());
        verify(testRail).addResults(77L, Map.of(11L, true, 12L, false), "Automated by AI Test Factory");
        assertThat(result.outputs().get("sync_report.md"))
                .contains("**testrail** cases + run + results: done — 2 case(s), run 77");
    }

    @Test
    void anOrdinaryRetryOfUnchangedArtifactsRepeatsNoCompletedAction() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(REPO, SECTION_ID, null);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        when(testRail.addCase(SECTION_ID, "[ERM-9] first", "s1", "e1")).thenReturn(11L);
        when(testRail.addCase(SECTION_ID, "[ERM-9] second", "s2", "e2")).thenReturn(12L);
        when(testRail.addRun(RUN_NAME, List.of(11L, 12L))).thenReturn(77L);

        worker(properties, ledger).execute(twoCaseContext());
        AgentResult retry = worker(properties, ledger).execute(twoCaseContext());

        verify(gitHub, times(1)).commitFiles(eq(REPO), eq(BRANCH), anyMap(), anyString());
        verify(gitHub, times(1)).createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString());
        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] first", "s1", "e1");
        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] second", "s2", "e2");
        verify(testRail, times(1)).addRun(RUN_NAME, List.of(11L, 12L));
        verify(jira, times(1)).addComment(eq(ISSUE_KEY), anyString());
        assertThat(retry.outputs().get("sync_report.md"))
                .contains("**github** branch + commit + PR: done — completed on an earlier attempt")
                .contains("**testrail** cases + run: done — completed on an earlier attempt")
                .contains("**jira** comment: done — completed on an earlier attempt");
    }

    /**
     * The gap this pins: the finalizer throws on a failed sync, so it escalates, and a
     * reviewer can AMEND the artifacts it declares as inputs before approving the retry.
     * An amendment is a new artifact version whose content differs — content-blind keys
     * would find every action already recorded, skip every connector and report the
     * corrected plan as synced when nothing was sent.
     */
    @Test
    void aRetryAfterThePlanWasAmendedResyncsOnlyTestRail() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(REPO, SECTION_ID, null);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        when(testRail.addCase(SECTION_ID, "[ERM-9] first", "s1", "e1")).thenReturn(11L, 21L);
        when(testRail.addCase(SECTION_ID, "[ERM-9] second", "s2", "e2")).thenReturn(12L);
        when(testRail.addCase(SECTION_ID, "[ERM-9] second, corrected", "s2", "e2")).thenReturn(23L);
        when(testRail.addRun(RUN_NAME, List.of(11L, 12L))).thenReturn(77L);
        when(testRail.addRun(RUN_NAME, List.of(21L, 23L))).thenReturn(78L);

        worker(properties, ledger).execute(twoCaseContext());
        AgentResult retry = worker(properties, ledger)
                .execute(context(twoCasePlan("second, corrected"), advisoryResults()));

        // An amended plan is a different set of cases, so TestRail gets a new set and a
        // new run over it — the created cases cannot be edited into the amended wording.
        verify(testRail).addCase(SECTION_ID, "[ERM-9] second, corrected", "s2", "e2");
        verify(testRail).addRun(RUN_NAME, List.of(21L, 23L));
        verify(gitHub, times(1)).commitFiles(eq(REPO), eq(BRANCH), anyMap(), anyString());
        verify(jira, times(1)).addComment(eq(ISSUE_KEY), anyString());
        assertThat(retry.outputs().get("sync_report.md"))
                .contains("**testrail** cases + run: done — 2 case(s), run 78")
                .contains("**github** branch + commit + PR: done — completed on an earlier attempt")
                .contains("**jira** comment: done — completed on an earlier attempt");
    }

    @Test
    void aRetryAfterTheScriptsWereAmendedPushesThemAndResyncsNothingElse() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(REPO, SECTION_ID, null);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL).thenThrow(unprocessable("A pull request already exists"));
        when(testRail.addCase(SECTION_ID, "[ERM-9] works", "s", "e")).thenReturn(1L);
        when(testRail.addRun(RUN_NAME, List.of(1L))).thenReturn(77L);

        worker(properties, ledger).execute(context());
        AgentResult retry = worker(properties, ledger).execute(context(onePlan("works"), advisoryResults(),
                scripts("Feature: A\n  Scenario: TC-01 works, corrected")));

        ArgumentCaptor<Map<String, String>> pushed = ArgumentCaptor.captor();
        verify(gitHub, times(2)).commitFiles(eq(REPO), eq(BRANCH), pushed.capture(), anyString());
        assertThat(pushed.getValue().get("features/a.feature")).contains("works, corrected");
        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] works", "s", "e");
        verify(testRail, times(1)).addRun(RUN_NAME, List.of(1L));
        verify(jira, times(1)).addComment(eq(ISSUE_KEY), anyString());
        // Re-pushing onto the same branch is safe: the open pull request picks the
        // amended bundle up, and GitHub's 422 for the existing PR still counts as done.
        assertThat(retry.outputs().get("sync_report.md"))
                .contains("**github** branch + commit + PR: done — pull request already exists for " + BRANCH)
                .contains("**testrail** cases + run: done — completed on an earlier attempt")
                .contains("**jira** comment: done — completed on an earlier attempt");
    }

    @Test
    void aRetryAfterAdvisoryResultsWereAmendedRecommentsAndLeavesTestRailAlone() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(REPO, SECTION_ID, null);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        when(testRail.addCase(SECTION_ID, "[ERM-9] works", "s", "e")).thenReturn(1L);
        when(testRail.addRun(RUN_NAME, List.of(1L))).thenReturn(77L);

        worker(properties, ledger).execute(context());
        AgentResult retry = worker(properties, ledger)
                .execute(context(onePlan("works"), advisoryResults("advisory — reviewer corrected the notice")));

        verify(jira, times(2)).addComment(eq(ISSUE_KEY), anyString());
        // Advisory results are never sent to TestRail, so amending them must not
        // re-enter that block only to claim work it would not redo.
        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] works", "s", "e");
        verify(testRail, times(1)).addRun(RUN_NAME, List.of(1L));
        verify(gitHub, times(1)).commitFiles(eq(REPO), eq(BRANCH), anyMap(), anyString());
        assertThat(retry.outputs().get("sync_report.md"))
                .contains("- **jira** comment: done\n")
                .contains("**testrail** cases + run: done — completed on an earlier attempt")
                .contains("**github** branch + commit + PR: done — completed on an earlier attempt");
    }

    /**
     * Executed results do reach TestRail, so amending them has to re-post against the
     * run the earlier attempt created — without recreating the cases or the run, whose
     * keys track the unchanged plan.
     */
    @Test
    void aRetryAfterExecutedResultsWereAmendedRepostsThemAgainstTheSameRun() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(null, SECTION_ID, null);
        when(testRail.addCase(SECTION_ID, "[ERM-9] first", "s1", "e1")).thenReturn(11L);
        when(testRail.addCase(SECTION_ID, "[ERM-9] second", "s2", "e2")).thenReturn(12L);
        when(testRail.addRun(RUN_NAME, List.of(11L, 12L))).thenReturn(77L);

        worker(properties, ledger).execute(twoCaseContext(executedResults()));
        AgentResult retry = worker(properties, ledger).execute(twoCaseContext(executedResults("PASSED")));

        verify(testRail, times(1)).addCase(SECTION_ID, "[ERM-9] first", "s1", "e1");
        verify(testRail, times(1)).addRun(RUN_NAME, List.of(11L, 12L));
        verify(testRail).addResults(77L, Map.of(11L, true, 12L, false), "Automated by AI Test Factory");
        verify(testRail).addResults(77L, Map.of(11L, true, 12L, true), "Automated by AI Test Factory");
        assertThat(retry.outputs().get("sync_report.md"))
                .contains("**testrail** cases + run + results: done — 2 case(s), run 77");
    }

    /**
     * Finding the pull request already opened is not enough — the URL only exists in
     * the ledger, and the Jira comment still outstanding has to carry it.
     */
    @Test
    void aRetryStillNamesThePullRequestTheEarlierAttemptOpened() {
        RecordingAuditSink ledger = new RecordingAuditSink();
        TestFactoryProperties properties = properties(REPO, null, null);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);
        doThrow(new RestClientException("Jira returned 503")).doNothing()
                .when(jira).addComment(eq(ISSUE_KEY), anyString());

        assertThatThrownBy(() -> worker(properties, ledger).execute(context()))
                .isInstanceOf(AgentExecutionException.class);
        worker(properties, ledger).execute(context());

        verify(gitHub, times(1)).createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString());
        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(jira, times(2)).addComment(eq(ISSUE_KEY), comment.capture());
        assertThat(comment.getAllValues().getLast()).contains("Test scripts PR: " + PR_URL);
    }

    @Test
    void anAlreadyPostedJiraCommentDoesNotBlockAnOutstandingTransition() {
        completed("jira", TestFactoryFinalizerWorker.JIRA_COMMENT, "");

        AgentResult result = worker(properties(null, null, "QA Complete")).execute(context());

        verify(jira, never()).addComment(anyString(), anyString());
        verify(jira).transitionIssue(ISSUE_KEY, "QA Complete");
        assertThat(result.outputs().get("sync_report.md"))
                .contains("**jira** transition 'QA Complete': done");
    }

    @Test
    void reusesAnExistingBranchAndStillOpensThePullRequest() {
        doThrow(unprocessable("Reference already exists")).when(gitHub).createBranch(REPO, "main", BRANCH);
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenReturn(PR_URL);

        AgentResult result = worker(properties(REPO, null, null)).execute(context());

        verify(gitHub).commitFiles(eq(REPO), eq(BRANCH), anyMap(), anyString());
        assertThat(result.outputs().get("sync_report.md")).contains(PR_URL);
    }

    @Test
    void anExistingPullRequestCountsAsDoneAndIsRecordedUnderTheSameActionKey() {
        when(gitHub.createPullRequest(eq(REPO), eq(BRANCH), eq("main"), anyString(), anyString()))
                .thenThrow(unprocessable("A pull request already exists"));

        AgentResult result = worker(properties(REPO, null, null)).execute(context());

        assertThat(result.outputs().get("sync_report.md"))
                .contains("**github** branch + commit + PR: done — pull request already exists for " + BRANCH);
        verify(auditSink).connectorAction(eq(EXECUTION_ID), eq(STEP_ID), eq(TestFactoryFinalizerWorker.ID),
                eq("github"), startsWith(TestFactoryFinalizerWorker.GITHUB_PR), eq("branch + commit + PR"),
                anyString());
    }

    @Test
    void logsAWarningWhenAConnectorSyncFails() {
        doThrow(new RestClientException("GitHub returned 500")).when(gitHub).createBranch(REPO, "main", BRANCH);
        Logger workerLogger = (Logger) LoggerFactory.getLogger(TestFactoryFinalizerWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);

        try {
            assertThatThrownBy(() -> worker(properties(REPO, null, null)).execute(context()))
                    .isInstanceOf(AgentExecutionException.class);
        } finally {
            workerLogger.detachAppender(appender);
        }

        // A failed sync must be visible in the log, not only in audit/metrics —
        // otherwise a connector outage is undiagnosable from application logs.
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("github").contains("GitHub returned 500");
        });
    }

    @Test
    void failsFastOnBlankIssueKeyBeforeAnySideEffect() {
        assertThatThrownBy(() -> worker(properties(REPO, SECTION_ID, "QA Complete"))
                .execute(context(codec.render(Map.of("cases", List.of()), "# Test Plan"))))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("no issue key");

        verifyNoInteractions(gitHub, testRail, jira, auditSink);
    }

    private TestFactoryFinalizerWorker worker(TestFactoryProperties properties) {
        return worker(properties, auditSink);
    }

    private TestFactoryFinalizerWorker worker(TestFactoryProperties properties, WorkerAuditSink sink) {
        return new TestFactoryFinalizerWorker(jira, gitHub, testRail, codec, bundleCodec,
                properties, sink, new EngineMetrics(new SimpleMeterRegistry()));
    }

    /** Matches on the base key so these cases stay independent of the content fingerprint. */
    private void completed(String connector, String actionKey, String detail) {
        when(auditSink.completedDetail(eq(EXECUTION_ID), eq(connector), startsWith(actionKey)))
                .thenReturn(Optional.of(detail));
    }

    private static String keyed(String actionKey, String artifactContent) {
        return actionKey + "@" + ArtifactStore.sha256(artifactContent).substring(0, 12);
    }

    private static TestFactoryProperties properties(String targetRepo, Long sectionId, String transition) {
        return new TestFactoryProperties(new TestFactoryProperties.Execution(null, null),
                targetRepo, "main", transition, sectionId);
    }

    private static HttpClientErrorException unprocessable(String message) {
        return HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_CONTENT, message,
                HttpHeaders.EMPTY, new byte[0], null);
    }

    private AgentContext context() {
        return context(onePlan("works"));
    }

    private String onePlan(String title) {
        return codec.render(Map.of("issue_key", ISSUE_KEY, "cases", List.of(
                Map.of("id", "TC-01", "title", title, "steps", List.of("s"), "expected", "e"))),
                "# Test Plan");
    }

    private AgentContext twoCaseContext() {
        return twoCaseContext(advisoryResults());
    }

    private AgentContext twoCaseContext(String resultsContent) {
        return context(twoCasePlan(), resultsContent);
    }

    private String twoCasePlan() {
        return twoCasePlan("second");
    }

    private String twoCasePlan(String secondTitle) {
        return codec.render(Map.of("issue_key", ISSUE_KEY, "cases", List.of(
                Map.of("id", "TC-01", "title", "first", "steps", List.of("s1"), "expected", "e1"),
                Map.of("id", "TC-02", "title", secondTitle, "steps", List.of("s2"), "expected", "e2"))),
                "# Test Plan");
    }

    private String advisoryResults() {
        return advisoryResults("advisory");
    }

    private String advisoryResults(String body) {
        return codec.render(Map.of("mode", "ADVISORY", "case_results", List.of()), body);
    }

    private String executedResults() {
        return executedResults("FAILED");
    }

    private String executedResults(String secondStatus) {
        return codec.render(Map.of("mode", "EXECUTED", "case_results", List.of(
                        Map.of("case_id", "TC-01", "status", "PASSED"),
                        Map.of("case_id", "TC-02", "status", secondStatus))),
                "executed");
    }

    private String scripts(String feature) {
        return bundleCodec.render(new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01"), feature))));
    }

    private AgentContext context(String planContent) {
        return context(planContent, advisoryResults());
    }

    private AgentContext context(String planContent, String resultsContent) {
        return context(planContent, resultsContent, scripts("Feature: A\n  Scenario: TC-01 works"));
    }

    private AgentContext context(String planContent, String resultsContent, String scriptsContent) {
        return new AgentContext(EXECUTION_ID, STEP_ID, Map.of(
                "test_plan.md", new ArtifactContent("test_plan.md", 1, "text/markdown", planContent),
                "test_scripts.md", new ArtifactContent("test_scripts.md", 1, "text/markdown", scriptsContent),
                "test_results.md", new ArtifactContent("test_results.md", 1, "text/markdown", resultsContent)),
                null, Map.of(), List.of("sync_report.md"));
    }

    /**
     * A ledger that can only answer from what the worker recorded through it, which is
     * the whole point: a stubbed completed-lookup would report an action as done that
     * the worker never keyed, and the duplicate call would go unnoticed.
     */
    private static final class RecordingAuditSink implements WorkerAuditSink {

        private final Map<String, String> completed = new LinkedHashMap<>();

        /** An action a previous attempt recorded before it died. */
        void seed(String connector, String actionKey, String detail) {
            connectorAction(EXECUTION_ID, STEP_ID, TestFactoryFinalizerWorker.ID,
                    connector, actionKey, actionKey, detail);
        }

        @Override
        public void connectorAction(UUID executionId, String stepId, String workerId,
                                    String connector, String actionKey, String label, String detail) {
            completed.putIfAbsent(executionId + "/" + connector + "/" + actionKey,
                    detail == null ? "" : detail);
        }

        @Override
        public void connectorSkipped(UUID executionId, String stepId, String workerId,
                                     String connector, String actionKey, String label, String detail) {
        }

        @Override
        public void connectorFailed(UUID executionId, String stepId, String workerId,
                                    String connector, String actionKey, String label, String detail) {
        }

        @Override
        public Optional<String> completedDetail(UUID executionId, String connector, String actionKey) {
            return Optional.ofNullable(completed.get(executionId + "/" + connector + "/" + actionKey));
        }
    }
}
