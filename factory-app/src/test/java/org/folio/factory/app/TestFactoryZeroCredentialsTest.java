package org.folio.factory.app;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The zero-credentials path: with no Jira/GitHub/TestRail configuration, the Test Factory flow
 * still runs end-to-end (inline issue payload, advisory execution) and records
 * every skipped side effect instead of failing.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250"
})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class TestFactoryZeroCredentialsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PipelineRouter router;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    HitlDecisionService decisionService;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void completesWithSkippedConnectorsWhenNothingIsConfigured() {
        UUID executionId = router.routeManual("test-factory", json.readTree("""
                {"issueKey": "ERM-2002",
                 "issue": {"key": "ERM-2002",
                           "fields": {"summary": "Inline story",
                                      "description": "AC1: something works",
                                      "status": {"name": "Ready for QA"}}}}
                """));

        approveGate(executionId, "gate-1-test-plan");
        approveGate(executionId, "gate-script-review");
        approveGate(executionId, "gate-2-signoff");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.COMPLETED));

        String syncReport = artifactStore.getLatest(executionId, "sync_report.md").orElseThrow().getContent();
        assertThat(syncReport)
                .contains("skipped")
                .contains("FACTORY_TEST_FACTORY_TARGET_REPO")
                .contains("FACTORY_CONNECTORS_JIRA_BASE_URL");

        long skippedEvents = auditLog.forExecution(executionId).stream()
                .filter(e -> e.getEventType() == AuditEventType.CONNECTOR_SKIPPED)
                .count();
        assertThat(skippedEvents).isGreaterThanOrEqualTo(3);

        String results = artifactStore.getLatest(executionId, "test_results.md").orElseThrow().getContent();
        assertThat(results).contains("mode: \"ADVISORY\"").contains("NOT_EXECUTED");
    }

    private void approveGate(UUID executionId, String gateId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                        .anyMatch(r -> r.getGateId().equals(gateId)
                                && r.getStatus() == HitlReviewStatus.PENDING)).isTrue());
        var review = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(r -> r.getGateId().equals(gateId) && r.getStatus() == HitlReviewStatus.PENDING)
                .findFirst().orElseThrow();
        decisionService.decide(review.getId(), HitlDecision.APPROVE, "qa-lead", null, null);
    }
}
