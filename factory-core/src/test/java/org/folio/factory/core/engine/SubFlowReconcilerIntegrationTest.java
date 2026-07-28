package org.folio.factory.core.engine;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maintenance sweeps must be per-child, not all-or-nothing: one child whose recovery
 * cannot succeed used to roll back — or never reach — every other child in the pass.
 * They must also be idempotent: a sweep runs every reap interval for as long as a
 * parent waits, so a child it has already accounted for must stop matching.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class SubFlowReconcilerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    SubFlowReconciler reconciler;

    @Autowired
    ExecutionEngine engine;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    HitlDecisionService hitlDecisions;

    @Autowired
    JdbcTemplate jdbc;

    /** A genuine fake-parent parked on its SUB_FLOW step, with its child created. */
    private PipelineExecution parkedParent() {
        PipelineExecution parent = stateManager.createExecution("fake-parent", "1.0.0", null);
        stateManager.transition(parent.getId(), ExecutionStatus.RUNNING, null);
        engine.advance(parent.getId());
        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
        return parent;
    }

    /**
     * A waiting parent whose flow is not registered, so every recovery unit for it
     * throws where a real one would hit an unmappable artifact or a purged descriptor.
     */
    private PipelineExecution poisonedParent(ExecutionStatus childStatus) {
        PipelineExecution parent = stateManager.createExecution("ghost-flow", "1.0.0", null);
        stateManager.transition(parent.getId(), ExecutionStatus.AWAITING_SUBFLOW, null);
        // parentStepIndex 0 = the parent's current step: the sweep only picks up a
        // child its parent is actually parked on.
        PipelineExecution child = stateManager.createChildExecution(
                "fake-child", "1.0.0", null, parent.getId(), 0);
        stateManager.transition(child.getId(), childStatus, null);
        return parent;
    }

    private PipelineExecution childOf(PipelineExecution parent) {
        return executions.findByParentExecutionId(parent.getId()).getFirst();
    }

    @Test
    void aChildThatCannotBeReconciledDoesNotBlockItsHealthySibling() {
        PipelineExecution poisoned = poisonedParent(ExecutionStatus.COMPLETED);
        PipelineExecution healthy = parkedParent();
        PipelineExecution healthyChild = childOf(healthy);
        artifactStore.putMarkdown(healthyChild.getId(), "child_output.md", "child result", "transform");
        stateManager.transition(healthyChild.getId(), ExecutionStatus.COMPLETED, null);

        reconciler.reconcileCompletedChildren();

        assertThat(artifactStore.getLatest(healthy.getId(), "collected.md"))
                .hasValueSatisfying(collected -> assertThat(collected.getContent()).isEqualTo("child result"));
        assertThat(stateManager.get(healthy.getId()).getCurrentStepIndex()).isEqualTo(2);
        assertThat(stateManager.get(poisoned.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
    }

    @Test
    void aChildThatCannotBeEscalatedDoesNotBlockItsHealthySibling() {
        PipelineExecution poisoned = poisonedParent(ExecutionStatus.REJECTED);
        PipelineExecution healthy = parkedParent();
        stateManager.transition(childOf(healthy).getId(), ExecutionStatus.REJECTED, null);

        reconciler.escalateParentsOfTerminatedChildren();

        assertThat(stateManager.get(healthy.getId()).getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(healthy.getId()))
                .singleElement()
                .satisfies(review -> assertThat(review.getGateId()).isEqualTo(HitlGateOpener.ESCALATION_GATE_ID));
        assertThat(stateManager.get(poisoned.getId()).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
    }

    /**
     * Drives a parent through rejection of its sub-flow, escalation, and approval of
     * that escalation — which re-invokes the sub-flow at the same step. Returns the
     * fresh child now running under the parent.
     */
    private PipelineExecution reinvokedChildAfterApprovedEscalation() {
        PipelineExecution parent = parkedParent();
        PipelineExecution rejectedChild = childOf(parent);
        stateManager.transition(rejectedChild.getId(), ExecutionStatus.REJECTED, null);
        reconciler.escalateParentsOfTerminatedChildren();
        assertThat(stateManager.get(parent.getId()).getStatus()).isEqualTo(ExecutionStatus.FAILED_ESCALATED);

        UUID reviewId = reviews.findByExecutionIdOrderByCreatedAtAsc(parent.getId()).getFirst().getId();
        hitlDecisions.decide(reviewId, HitlDecision.APPROVE, "qa", null, null);
        stateManager.transition(parent.getId(), ExecutionStatus.RUNNING, null);
        engine.advance(parent.getId());
        return executions.findByParentExecutionId(parent.getId()).stream()
                .filter(child -> !child.getId().equals(rejectedChild.getId()))
                .findFirst().orElseThrow();
    }

    private void assertParentStillWaitsOn(PipelineExecution freshChild) {
        UUID parentId = freshChild.getParentExecutionId();
        assertThat(stateManager.get(parentId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(parentId))
                .describedAs("a second escalation review means the approved one was undone")
                .hasSize(1);
        assertThat(stateManager.get(freshChild.getId()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }

    /**
     * Approving a sub-flow escalation re-invokes the sub-flow at the same step under a
     * fresh child. The rejected child that caused the escalation must stop matching, or
     * the next sweep escalates the parent again and orphans the child now legitimately
     * running under it.
     */
    @Test
    void approvingASubFlowEscalationSurvivesTheNextSweep() {
        PipelineExecution freshChild = reinvokedChildAfterApprovedEscalation();

        reconciler.escalateParentsOfTerminatedChildren();

        assertParentStillWaitsOn(freshChild);
    }

    /**
     * Supersession must not rest on a wall clock. created_at is stamped by whichever
     * instance inserted the row, so a backward NTP step — or skew between instances in
     * a multi-instance deployment — can leave the fresh child no newer than the one it
     * replaced. Backdating the fresh child relative to its predecessor must change
     * nothing: the parent records the invocation it is parked on.
     */
    @Test
    void approvingASubFlowEscalationSurvivesASupersededChildWithANewerTimestamp() {
        PipelineExecution freshChild = reinvokedChildAfterApprovedEscalation();
        jdbc.update("UPDATE pipeline_execution SET created_at = ? WHERE parent_execution_id = ? AND id <> ?",
                Timestamp.from(freshChild.getCreatedAt().plusSeconds(60)),
                freshChild.getParentExecutionId(), freshChild.getId());

        reconciler.escalateParentsOfTerminatedChildren();

        assertParentStillWaitsOn(freshChild);
    }

    /**
     * With two SUB_FLOW steps in one chain, the first step's COMPLETED child stays
     * COMPLETED while the parent waits on the second. Re-handing it back would append a
     * stale artifact version on every sweep for the whole life of the second sub-flow.
     */
    @Test
    void aFinishedSubFlowStepIsNotHandedBackAgainWhileTheNextOneRuns() {
        PipelineExecution parent = stateManager.createExecution("fake-two-subflows", "1.0.0", null);
        stateManager.transition(parent.getId(), ExecutionStatus.RUNNING, null);
        engine.advance(parent.getId());
        PipelineExecution firstChild = childOf(parent);
        stateManager.transition(firstChild.getId(), ExecutionStatus.RUNNING, null);
        engine.advance(firstChild.getId());
        stateManager.transition(parent.getId(), ExecutionStatus.RUNNING, null);
        engine.advance(parent.getId());
        assertThat(stateManager.get(parent.getId()).getCurrentStepIndex()).isEqualTo(2);
        int handedBackVersion = artifactStore.getLatest(parent.getId(), "collected.md").orElseThrow().getVersion();

        assertThat(stateManager.findCompletedChildrenWithWaitingParent())
                .describedAs("the first step's child is not the invocation the parent is waiting on")
                .noneMatch(child -> child.getId().equals(firstChild.getId()));
        reconciler.reconcileCompletedChildren();
        reconciler.reconcileCompletedChildren();

        assertThat(artifactStore.getLatest(parent.getId(), "collected.md").orElseThrow().getVersion())
                .isEqualTo(handedBackVersion);
        PipelineExecution parked = stateManager.get(parent.getId());
        assertThat(parked.getStatus()).isEqualTo(ExecutionStatus.AWAITING_SUBFLOW);
        assertThat(parked.getCurrentStepIndex()).isEqualTo(2);
    }
}
