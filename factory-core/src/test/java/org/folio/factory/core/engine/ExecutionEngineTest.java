package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-of-chain completion semantics: the parent hand-off must only happen when
 * the COMPLETED transition actually took effect — a guard-refused transition
 * (concurrent cancel) must not resume a waiting parent on a dead child.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionEngineTest {

    private static final String FLOW_ID = "flow-under-test";
    private static final FlowDescriptor EMPTY_FLOW =
            new FlowDescriptor(FLOW_ID, FLOW_ID, "1.0.0", null, null, null, List.of(), null);

    @Mock
    FlowRegistry flowRegistry;

    @Mock
    AgentWorkerRegistry workerRegistry;

    @Mock
    StateManager stateManager;

    @Mock
    ArtifactStore artifactStore;

    @Mock
    AuditLog auditLog;

    @Mock
    HitlGateOpener hitlGateOpener;

    @Mock
    SubFlowInvoker subFlowInvoker;

    @Mock
    StepLeaseHeartbeat stepLeaseHeartbeat;

    @Mock
    JsonMapper jsonMapper;

    @Mock
    EngineMetrics engineMetrics;

    ExecutionEngine engine;

    final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new ExecutionEngine(flowRegistry, workerRegistry, stateManager, artifactStore,
                auditLog, hitlGateOpener, subFlowInvoker, stepLeaseHeartbeat, List.of(), jsonMapper, engineMetrics);
    }

    private PipelineExecution executionIn(ExecutionStatus status) {
        PipelineExecution execution = new PipelineExecution(FLOW_ID, "1.0.0", null);
        execution.setStatus(status);
        return execution;
    }

    @Test
    void endOfChainCompletionResumesWaitingParent() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        when(stateManager.get(executionId)).thenReturn(running);
        when(flowRegistry.require(FLOW_ID)).thenReturn(EMPTY_FLOW);
        when(stateManager.transition(running.getId(), ExecutionStatus.COMPLETED, null))
                .thenReturn(executionIn(ExecutionStatus.COMPLETED));

        engine.advance(executionId);

        verify(subFlowInvoker).onChildCompleted(running.getId());
    }

    @Test
    void guardRefusedCompletionDoesNotResumeWaitingParent() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        when(stateManager.get(executionId)).thenReturn(running);
        when(flowRegistry.require(FLOW_ID)).thenReturn(EMPTY_FLOW);
        // The run was cancelled between the loop-top status read and the terminal
        // transition: the guard leaves it CANCELLED.
        when(stateManager.transition(running.getId(), ExecutionStatus.COMPLETED, null))
                .thenReturn(executionIn(ExecutionStatus.CANCELLED));

        engine.advance(executionId);

        verifyNoInteractions(subFlowInvoker);
    }
}
