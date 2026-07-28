package org.folio.factory.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_execution")
public class PipelineExecution {

    @Id
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private String flowId;

    @Column(name = "flow_version", nullable = false)
    private String flowVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;

    @Column(name = "current_step_index", nullable = false)
    private int currentStepIndex;

    @Column(name = "parent_execution_id")
    private UUID parentExecutionId;

    @Column(name = "parent_step_index")
    private Integer parentStepIndex;

    @Column(name = "awaiting_child_execution_id")
    private UUID awaitingChildExecutionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_payload")
    private String triggerPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_counts", nullable = false)
    private String retryCounts = "{}";

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PipelineExecution() {
    }

    public PipelineExecution(String flowId, String flowVersion, String triggerPayload) {
        this.id = UUID.randomUUID();
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.triggerPayload = triggerPayload;
        this.status = ExecutionStatus.PENDING;
        this.currentStepIndex = 0;
        this.nextRunAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getFlowVersion() {
        return flowVersion;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
        if (status.isTerminal()) {
            this.completedAt = Instant.now();
        }
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public void setCurrentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    public UUID getParentExecutionId() {
        return parentExecutionId;
    }

    public void setParentExecutionId(UUID parentExecutionId) {
        this.parentExecutionId = parentExecutionId;
    }

    public Integer getParentStepIndex() {
        return parentStepIndex;
    }

    public void setParentStepIndex(Integer parentStepIndex) {
        this.parentStepIndex = parentStepIndex;
    }

    /**
     * The sub-flow child this execution is parked on, when it is AWAITING_SUBFLOW.
     * Overwritten by every child creation, so recovery sweeps can tell the live
     * invocation from one a re-invocation superseded without comparing clocks.
     */
    public UUID getAwaitingChildExecutionId() {
        return awaitingChildExecutionId;
    }

    public void setAwaitingChildExecutionId(UUID awaitingChildExecutionId) {
        this.awaitingChildExecutionId = awaitingChildExecutionId;
    }

    public String getTriggerPayload() {
        return triggerPayload;
    }

    public String getRetryCounts() {
        return retryCounts;
    }

    public void setRetryCounts(String retryCounts) {
        this.retryCounts = retryCounts;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
