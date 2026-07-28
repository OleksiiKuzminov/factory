package org.folio.factory.core.service;

import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The audit surface plugins get: attribution they cannot forge, and the
 * completed-once record that keeps non-idempotent side effects from being repeated
 * when a step re-runs.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class WorkerAuditSinkIntegrationTest {

    private static final String WORKER = "finalizer-agent";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    WorkerAuditSink sink;

    @Autowired
    AuditLog auditLog;

    @Autowired
    StateManager stateManager;

    private UUID newExecution() {
        return stateManager.createExecution("test-flow", "1.0.0", null).getId();
    }

    private List<AuditEvent> connectorEvents(UUID executionId) {
        return auditLog.forExecution(executionId).stream()
                .filter(event -> event.getEventType() != AuditEventType.EXECUTION_STARTED)
                .toList();
    }

    @Test
    void everyConnectorOutcomeIsRecordedUnderTheWorkersOwnActor() {
        UUID executionId = newExecution();

        sink.connectorAction(executionId, "finalize", WORKER, "jira", "jira.comment",
                "Commented on ERM-1", "1 comment");
        sink.connectorSkipped(executionId, "finalize", WORKER, "github", "github.pr",
                "GitHub is not configured", null);
        sink.connectorFailed(executionId, "finalize", WORKER, "testrail", "testrail.run",
                "TestRail rejected the run", "HTTP 500");

        List<AuditEvent> events = connectorEvents(executionId);
        assertThat(events).extracting(AuditEvent::getEventType).containsExactly(
                AuditEventType.CONNECTOR_ACTION,
                AuditEventType.CONNECTOR_SKIPPED,
                AuditEventType.CONNECTOR_FAILED);
        assertThat(events).extracting(AuditEvent::getActor).containsOnly("worker:" + WORKER);
        assertThat(events).extracting(AuditEvent::getStepId).containsOnly("finalize");
        assertThat(events.getFirst().getDetail())
                .containsIgnoringWhitespaces("\"connector\":\"jira\"")
                .containsIgnoringWhitespaces("\"actionKey\":\"jira.comment\"")
                .containsIgnoringWhitespaces("\"action\":\"Commented on ERM-1\"")
                .containsIgnoringWhitespaces("\"detail\":\"1 comment\"");
    }

    @Test
    void aCompletedActionIsRecognisedOnlyForItsOwnExecutionConnectorAndActionKey() {
        UUID executionId = newExecution();
        sink.connectorAction(executionId, "finalize", WORKER, "jira", "jira.comment", "Commented", null);

        assertThat(sink.completedDetail(executionId, "jira", "jira.comment")).isPresent();
        assertThat(sink.completedDetail(executionId, "jira", "jira.transition")).isEmpty();
        assertThat(sink.completedDetail(executionId, "github", "jira.comment")).isEmpty();
        assertThat(sink.completedDetail(newExecution(), "jira", "jira.comment")).isEmpty();
    }

    /**
     * The identifier a non-idempotent call produced is only reachable through the
     * ledger once the step has re-run, so it has to survive the round trip: without
     * it the retry cannot finish the outstanding work without creating a duplicate.
     */
    @Test
    void aCompletedActionCarriesItsRecordedDetailBackToTheNextAttempt() {
        UUID executionId = newExecution();
        sink.connectorAction(executionId, "finalize", WORKER, "testrail", "testrail.case:TC-01",
                "case TC-01", "4711");

        assertThat(sink.completedDetail(executionId, "testrail", "testrail.case:TC-01")).contains("4711");
    }

    /** Present-but-empty must stay distinguishable from absent: the action did happen. */
    @Test
    void aCompletedActionWithoutADetailIsStillReportedAsCompleted() {
        UUID executionId = newExecution();
        sink.connectorAction(executionId, "finalize", WORKER, "jira", "jira.transition", "Transitioned", null);

        assertThat(sink.completedDetail(executionId, "jira", "jira.transition")).contains("");
    }

    @Test
    void aSkippedOrFailedSideEffectDoesNotCountAsCompleted() {
        UUID executionId = newExecution();
        sink.connectorSkipped(executionId, "finalize", WORKER, "github", "github.pr", "Not configured", null);
        sink.connectorFailed(executionId, "finalize", WORKER, "testrail", "testrail.run", "Rejected", null);

        assertThat(sink.completedDetail(executionId, "github", "github.pr")).isEmpty();
        assertThat(sink.completedDetail(executionId, "testrail", "testrail.run")).isEmpty();
    }

    @Test
    void aBlankActionKeyIsRejectedBecauseNothingCouldEverMatchIt() {
        UUID executionId = newExecution();

        assertThatIllegalArgumentException().isThrownBy(() ->
                sink.connectorAction(executionId, "finalize", WORKER, "jira", " ", "Commented", null));
        assertThat(connectorEvents(executionId)).isEmpty();
    }
}
