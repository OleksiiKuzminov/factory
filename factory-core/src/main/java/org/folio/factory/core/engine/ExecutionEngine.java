package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The control plane's state machine: advances a pipeline execution through its
 * flow descriptor's agent chain, one step at a time. Entirely data-driven — the
 * engine has no knowledge of any specific flow.
 *
 * <p>Deliberately NOT transactional as a whole: agent workers can run for
 * minutes, so each state mutation (artifact writes, step advancement, retry
 * bookkeeping) commits in its own short transaction via the core services. A
 * crash mid-step is recovered by the poller's lease reaper; re-running a step
 * writes new artifact versions rather than corrupting old ones.</p>
 */
@Component
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final FlowRegistry flowRegistry;
    private final AgentWorkerRegistry workerRegistry;
    private final StateManager stateManager;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final HitlGateOpener hitlGateOpener;
    private final SubFlowInvoker subFlowInvoker;
    private final StepLeaseHeartbeat stepLeaseHeartbeat;
    private final List<StepPostProcessor> postProcessors;
    private final JsonMapper jsonMapper;
    private final EngineMetrics engineMetrics;

    public ExecutionEngine(FlowRegistry flowRegistry,
                           AgentWorkerRegistry workerRegistry,
                           StateManager stateManager,
                           ArtifactStore artifactStore,
                           AuditLog auditLog,
                           HitlGateOpener hitlGateOpener,
                           SubFlowInvoker subFlowInvoker,
                           StepLeaseHeartbeat stepLeaseHeartbeat,
                           List<StepPostProcessor> postProcessors,
                           JsonMapper jsonMapper,
                           EngineMetrics engineMetrics) {
        this.flowRegistry = flowRegistry;
        this.workerRegistry = workerRegistry;
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.hitlGateOpener = hitlGateOpener;
        this.subFlowInvoker = subFlowInvoker;
        this.stepLeaseHeartbeat = stepLeaseHeartbeat;
        this.postProcessors = postProcessors;
        this.jsonMapper = jsonMapper;
        this.engineMetrics = engineMetrics;
    }

    /**
     * Advances the execution until it pauses (HITL gate, sub-flow, retry backoff),
     * fails, or completes. The execution must already be in RUNNING status.
     */
    public void advance(UUID executionId) {
        // Every log line emitted while this execution advances carries executionId.
        // Removed in finally: engine threads come from a pooled AsyncTaskExecutor and
        // are reused across executions, so the key must not leak to the next task.
        MDC.put("executionId", executionId.toString());
        try {
            while (true) {
                PipelineExecution execution = stateManager.get(executionId);
                if (execution.getStatus() != ExecutionStatus.RUNNING) {
                    return;
                }
                FlowDescriptor flow = flowRegistry.require(execution.getFlowId());
                if (!flow.hasStep(execution.getCurrentStepIndex())) {
                    complete(execution);
                    return;
                }
                StepDescriptor step = flow.step(execution.getCurrentStepIndex());
                boolean advanced = switch (step.type()) {
                    case AGENT -> runAgentStep(execution, flow, step);
                    case HITL_GATE -> {
                        hitlGateOpener.openGate(execution, step);
                        yield false;
                    }
                    case SUB_FLOW -> {
                        subFlowInvoker.invoke(execution, step);
                        yield false;
                    }
                };
                if (!advanced) {
                    return;
                }
            }
        } catch (Exception e) {
            // Defensive net for engine bugs and unknown flows — never leave an
            // execution stuck in RUNNING.
            log.error("Engine failure while advancing execution {}", executionId, e);
            failTerminally(executionId, e);
        } finally {
            MDC.remove("executionId");
        }
    }

    private boolean runAgentStep(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step) {
        UUID executionId = execution.getId();
        MDC.put("stepId", step.stepId());
        try {
            stateManager.heartbeat(executionId);
            auditLog.record(executionId, AuditEventType.STEP_STARTED, step.stepId(),
                    Map.of("workerId", step.workerId(), "attempt", stateManager.retryCount(executionId, step.stepId()) + 1));
            long startNanos = System.nanoTime();
            // Recorded exactly once in finally, classified by whether the step reached
            // success — so a fault in advanceStep is not counted as both success and failure.
            boolean success = false;
            try {
                AgentWorker worker = workerRegistry.require(step.workerId());
                AgentContext context = buildContext(execution, step);
                AgentResult result;
                // The lease must stay fresh for the whole worker call, not just at step
                // entry: a reaped step is driven again concurrently, and the guarded
                // advance below protects the cursor but not the worker's side effects.
                try (AutoCloseable lease = stepLeaseHeartbeat.start(executionId)) {
                    result = worker.execute(context);
                }
                requireDeclaredOutputs(step, result);
                for (StepPostProcessor postProcessor : postProcessors) {
                    postProcessor.process(flow, step, result.outputs());
                }
                for (Map.Entry<String, String> output : result.outputs().entrySet()) {
                    artifactStore.putMarkdown(executionId, output.getKey(), output.getValue(), step.stepId());
                }
                auditLog.record(executionId, AuditEventType.STEP_COMPLETED, step.stepId(), result.metrics());
                engineMetrics.recordLlmTokens(step.workerId(), result.metrics());
                // Guarded advance: if a duplicate driver (lease-reaped run) moved the
                // execution meanwhile, stop instead of double-advancing past a step.
                boolean advanced = stateManager.advanceStep(executionId, execution.getCurrentStepIndex(),
                        ExecutionStatus.RUNNING);
                success = true;
                return advanced;
            } catch (Exception e) {
                handleStepFailure(execution, flow, step, e);
                return false;
            } finally {
                engineMetrics.recordAgentStep(step.workerId(), success, Duration.ofNanos(System.nanoTime() - startNanos));
            }
        } finally {
            MDC.remove("stepId");
        }
    }

    private AgentContext buildContext(PipelineExecution execution, StepDescriptor step) {
        Map<String, ArtifactContent> inputs = new HashMap<>();
        for (String inputName : step.inputs()) {
            if (StepDescriptor.TRIGGER_INPUT.equals(inputName)) {
                continue;
            }
            var artifact = artifactStore.getLatest(execution.getId(), inputName)
                    .orElseThrow(() -> new AgentExecutionException(
                            "Step '" + step.stepId() + "' requires artifact '" + inputName + "' which does not exist"));
            inputs.put(inputName, new ArtifactContent(artifact.getName(), artifact.getVersion(),
                    artifact.getContentType(), artifact.getContent()));
        }
        JsonNode triggerPayload = null;
        if (step.wantsTriggerPayload() && execution.getTriggerPayload() != null) {
            triggerPayload = jsonMapper.readTree(execution.getTriggerPayload());
        }
        return new AgentContext(execution.getId(), step.stepId(), inputs, triggerPayload,
                step.config(), step.outputs());
    }

    private void requireDeclaredOutputs(StepDescriptor step, AgentResult result) {
        for (String declared : step.outputs()) {
            if (!result.outputs().containsKey(declared)) {
                throw new AgentExecutionException(
                        "Worker '" + step.workerId() + "' did not produce declared output artifact '" + declared + "'");
            }
        }
        Set<String> undeclared = new TreeSet<>(result.outputs().keySet());
        step.outputs().forEach(undeclared::remove);
        if (!undeclared.isEmpty()) {
            throw new AgentExecutionException(
                    "Worker '" + step.workerId() + "' produced undeclared output artifact(s) " + undeclared
                            + "; declared outputs: " + step.outputs());
        }
    }

    private void handleStepFailure(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step, Exception e) {
        UUID executionId = execution.getId();
        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        auditLog.record(executionId, AuditEventType.STEP_FAILED, step.stepId(), Map.of("error", error));
        int attempts = stateManager.incrementRetry(executionId, step.stepId());
        if (attempts < flow.retryPolicy().maxAttempts()) {
            long backoff = flow.retryPolicy().backoffFor(attempts);
            // Skip the retry bookkeeping when the guard refused the state change —
            // e.g. the run was cancelled while this step was still in flight.
            if (stateManager.scheduleRetry(executionId, backoff, error)) {
                auditLog.record(executionId, AuditEventType.RETRY_SCHEDULED, step.stepId(),
                        Map.of("attempt", attempts, "backoffSeconds", backoff));
                engineMetrics.stepRetryScheduled();
                log.warn("Step '{}' of execution {} failed (attempt {}); retrying in {}s: {}",
                        step.stepId(), executionId, attempts, backoff, error);
            } else {
                log.warn("Step '{}' of execution {} failed but the run is already resolved; skipping retry",
                        step.stepId(), executionId);
            }
        } else if (hitlGateOpener.openEscalationReview(execution, step, error, attempts) != null) {
            // openEscalationReview transitions to FAILED_ESCALATED and inserts the
            // review atomically; a null return means the guard refused the
            // transition (a concurrent resolution), so no review exists to attribute.
            auditLog.record(executionId, AuditEventType.ESCALATED, step.stepId(),
                    Map.of("attempts", attempts, "error", error));
            engineMetrics.stepEscalated();
            log.error("Step '{}' of execution {} exhausted its retry budget after {} attempts; escalated to human review",
                    step.stepId(), executionId, attempts);
        } else {
            log.warn("Step '{}' of execution {} exhausted retries but the run is already resolved; skipping escalation",
                    step.stepId(), executionId);
        }
    }

    private void complete(PipelineExecution execution) {
        PipelineExecution current = stateManager.transition(execution.getId(), ExecutionStatus.COMPLETED, null);
        // Guard refusal means a concurrent resolution (e.g. cancel) won: the run did
        // not complete, so a waiting parent must not resume on this child's outputs —
        // the terminated-children sweep escalates it instead.
        if (current.getStatus() == ExecutionStatus.COMPLETED) {
            subFlowInvoker.onChildCompleted(execution.getId());
        }
    }

    private void failTerminally(UUID executionId, Exception e) {
        try {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED, Map.of("engineError", error));
            auditLog.record(executionId, AuditEventType.ESCALATED, null, Map.of("engineError", error));
        } catch (Exception secondary) {
            log.error("Could not record terminal failure for execution {}", executionId, secondary);
        }
    }
}
