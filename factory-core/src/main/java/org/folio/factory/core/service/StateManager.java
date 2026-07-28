package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns every pipeline execution state transition. All mutating operations load the
 * execution inside the current transaction, so callers can safely pass ids from
 * any context. Each transition is persisted in the same transaction as its audit
 * event.
 */
@Service
public class StateManager {

    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private static final TypeReference<Map<String, Integer>> RETRY_COUNTS_TYPE = new TypeReference<>() {
    };

    private final PipelineExecutionRepository executions;
    private final AuditLog auditLog;
    private final JsonMapper jsonMapper;
    private final EngineMetrics metrics;

    public StateManager(PipelineExecutionRepository executions, AuditLog auditLog, JsonMapper jsonMapper,
                        EngineMetrics metrics) {
        this.executions = executions;
        this.auditLog = auditLog;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
    }

    @Transactional
    public PipelineExecution createExecution(String flowId, String flowVersion, String triggerPayloadJson) {
        return createExecution(flowId, flowVersion, triggerPayloadJson, null);
    }

    @Transactional
    public PipelineExecution createExecution(String flowId, String flowVersion, String triggerPayloadJson,
                                             String dedupKey) {
        PipelineExecution execution = new PipelineExecution(flowId, flowVersion, triggerPayloadJson);
        execution.setDedupKey(dedupKey);
        // Flush now so a unique-dedup collision surfaces as a catchable
        // DataIntegrityViolationException here rather than at a later commit.
        PipelineExecution saved = executions.saveAndFlush(execution);
        auditLog.record(saved.getId(), AuditEventType.EXECUTION_STARTED, null,
                Map.of("flowId", flowId, "flowVersion", flowVersion));
        metrics.executionStarted();
        return saved;
    }

    /**
     * Creates a sub-flow child and records it on the parent as the invocation the
     * parent is now waiting on, superseding any earlier child of the same step. That
     * pointer is what the recovery sweeps correlate on.
     */
    @Transactional
    public PipelineExecution createChildExecution(String flowId, String flowVersion, String triggerPayloadJson,
                                                  UUID parentExecutionId, int parentStepIndex) {
        PipelineExecution execution = new PipelineExecution(flowId, flowVersion, triggerPayloadJson);
        execution.setParentExecutionId(parentExecutionId);
        execution.setParentStepIndex(parentStepIndex);
        PipelineExecution saved = executions.save(execution);
        PipelineExecution parent = load(parentExecutionId);
        parent.setAwaitingChildExecutionId(saved.getId());
        executions.save(parent);
        auditLog.record(saved.getId(), AuditEventType.EXECUTION_STARTED, null,
                Map.of("flowId", flowId, "flowVersion", flowVersion,
                        "parentExecutionId", parentExecutionId.toString()));
        metrics.executionStarted();
        return saved;
    }

    @Transactional(readOnly = true)
    public PipelineExecution get(UUID executionId) {
        return load(executionId);
    }

    @Transactional(readOnly = true)
    public Optional<PipelineExecution> findRecentDuplicate(String flowId, String dedupKey, Duration window) {
        Instant since = Instant.now().minus(window);
        return executions.findDuplicates(flowId, dedupKey, since, ExecutionStatus.terminalStatuses())
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public long countCreatedSince(Instant since) {
        return executions.countByCreatedAtGreaterThanEqual(since);
    }

    /**
     * True when another non-terminal execution holds the same (flowId, dedupKey) —
     * i.e. requeueing this one would collide with the partial unique dedup index.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveDuplicate(PipelineExecution execution) {
        return execution.getDedupKey() != null
                && executions.existsByFlowIdAndDedupKeyAndIdNotAndStatusNotIn(
                        execution.getFlowId(), execution.getDedupKey(), execution.getId(),
                        ExecutionStatus.terminalStatuses());
    }

    /**
     * Children that ended without completing while their parent still waits.
     * FAILED_ESCALATED is deliberately NOT terminal here: such a child has a
     * pending escalation review and can still be resumed, so the parent keeps
     * waiting — per the design, a parent waits through its sub-flow's reviews.
     *
     * <p>Escalating is idempotent per invocation because the query only returns the
     * child the parent is actually parked on: once an approved escalation re-invokes
     * the sub-flow, the child that caused it is superseded and stops matching, so the
     * fresh child running under the parent is never orphaned by a second
     * escalation.</p>
     */
    @Transactional(readOnly = true)
    public List<PipelineExecution> findTerminatedChildren() {
        return executions.findChildrenWithWaitingParent(
                List.of(ExecutionStatus.REJECTED, ExecutionStatus.CANCELLED),
                ExecutionStatus.AWAITING_SUBFLOW);
    }

    /**
     * Children that completed but whose parent is still waiting — happens when the
     * process died between the child's COMPLETED transition and the parent
     * hand-off.
     */
    @Transactional(readOnly = true)
    public List<PipelineExecution> findCompletedChildrenWithWaitingParent() {
        return executions.findChildrenWithWaitingParent(
                List.of(ExecutionStatus.COMPLETED), ExecutionStatus.AWAITING_SUBFLOW);
    }

    @Transactional
    public PipelineExecution transition(UUID executionId, ExecutionStatus newStatus, Map<String, ?> auditDetail) {
        PipelineExecution execution = load(executionId);
        ExecutionStatus previous = execution.getStatus();
        if (previous.isFinal()) {
            // A resolved execution (COMPLETED/REJECTED/CANCELLED) must never be
            // resurrected by a late writer — e.g. an in-flight worker finishing
            // after the run was cancelled. FAILED_ESCALATED is deliberately not
            // final, so escalation resume still transitions freely.
            log.warn("Refusing transition of final execution {} ({} -> {})", executionId, previous, newStatus);
            return execution;
        }
        execution.setStatus(newStatus);
        PipelineExecution saved = executions.save(execution);
        Map<String, Object> detail = new HashMap<>();
        detail.put("from", previous.name());
        detail.put("to", newStatus.name());
        if (auditDetail != null) {
            detail.putAll(auditDetail);
        }
        auditLog.record(saved.getId(), AuditEventType.STATE_TRANSITION, null, AuditLog.SYSTEM_ACTOR, detail);
        if (newStatus == ExecutionStatus.COMPLETED) {
            auditLog.record(saved.getId(), AuditEventType.EXECUTION_COMPLETED, null, null);
        }
        if (newStatus.isFinal()) {
            // isFinal (not isTerminal) excludes FAILED_ESCALATED, which is resumable
            // via its escalation review — counting it would double-count a run that
            // later resolves to COMPLETED/REJECTED/CANCELLED.
            metrics.executionFinished(newStatus.name().toLowerCase(Locale.ROOT));
        }
        return saved;
    }

    /**
     * Increments the retry count for a step and returns the new count.
     */
    @Transactional
    public int incrementRetry(UUID executionId, String stepId) {
        PipelineExecution execution = load(executionId);
        Map<String, Integer> counts = readRetryCounts(execution);
        int next = counts.merge(stepId, 1, Integer::sum);
        execution.setRetryCounts(jsonMapper.writeValueAsString(counts));
        executions.save(execution);
        return next;
    }

    @Transactional
    public void resetRetry(UUID executionId, String stepId) {
        PipelineExecution execution = load(executionId);
        Map<String, Integer> counts = readRetryCounts(execution);
        counts.remove(stepId);
        execution.setRetryCounts(jsonMapper.writeValueAsString(counts));
        executions.save(execution);
    }

    @Transactional(readOnly = true)
    public int retryCount(UUID executionId, String stepId) {
        return readRetryCounts(load(executionId)).getOrDefault(stepId, 0);
    }

    /**
     * @return true when the retry was scheduled, false when refused because the
     *         execution has already resolved — callers must skip the audit/metrics
     *         they would otherwise record for a scheduled retry.
     */
    @Transactional
    public boolean scheduleRetry(UUID executionId, long backoffSeconds, String errorMessage) {
        PipelineExecution execution = load(executionId);
        if (execution.getStatus().isFinal()) {
            log.warn("Refusing scheduleRetry of final execution {} (status {})", executionId, execution.getStatus());
            return false;
        }
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setNextRunAt(Instant.now().plusSeconds(backoffSeconds));
        execution.setErrorMessage(errorMessage);
        executions.save(execution);
        return true;
    }

    /**
     * Advances the step cursor only when the execution is still where the caller
     * believes it is. Protects against duplicate drivers (a lease-reaped run
     * finishing late, a stale HITL decision) silently skipping steps.
     *
     * @return false when the execution has moved on and the caller must stop
     */
    @Transactional
    public boolean advanceStep(UUID executionId, int expectedStepIndex, ExecutionStatus expectedStatus) {
        PipelineExecution execution = load(executionId);
        if (execution.getStatus() != expectedStatus
                || execution.getCurrentStepIndex() != expectedStepIndex) {
            log.warn("Refusing stale step advance for execution {}: expected step {} in {}, found step {} in {}",
                    executionId, expectedStepIndex, expectedStatus,
                    execution.getCurrentStepIndex(), execution.getStatus());
            return false;
        }
        execution.setCurrentStepIndex(expectedStepIndex + 1);
        executions.save(execution);
        return true;
    }

    /**
     * Marks the execution as alive so the lease reaper does not re-queue it while
     * a long-running step (LLM call, test execution) is still in flight.
     */
    @Transactional
    public void heartbeat(UUID executionId) {
        PipelineExecution execution = load(executionId);
        execution.touchUpdatedAt();
        executions.save(execution);
    }

    private PipelineExecution load(UUID executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("No pipeline execution " + executionId));
    }

    private Map<String, Integer> readRetryCounts(PipelineExecution execution) {
        String json = execution.getRetryCounts();
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        return new HashMap<>(jsonMapper.readValue(json, RETRY_COUNTS_TYPE));
    }
}
