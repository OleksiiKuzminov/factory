package org.folio.factory.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The maintenance pass recovers three unrelated kinds of stall, so one failing
 * recovery must not suppress the others.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionPollerTest {

    private static final long DEFAULT_LEASE_TIMEOUT_SECONDS = 1800L;

    @Mock
    ExecutionClaimService claimService;

    @Mock
    ExecutionEngine engine;

    @Mock
    SubFlowReconciler subFlowReconciler;

    @Mock
    AsyncTaskExecutor executor;

    ExecutionPoller poller;

    @BeforeEach
    void setUp() {
        poller = new ExecutionPoller(claimService, engine, subFlowReconciler,
                new EngineProperties(null, null, null, null, null, null, null, null, null), executor);
    }

    @Test
    void aFailingLeaseReapStillLetsBothSubFlowSweepsRun() {
        when(claimService.reapStale(DEFAULT_LEASE_TIMEOUT_SECONDS))
                .thenThrow(new IllegalStateException("database unavailable"));

        poller.maintain();

        verify(subFlowReconciler).escalateParentsOfTerminatedChildren();
        verify(subFlowReconciler).reconcileCompletedChildren();
    }

    @Test
    void aFailingEscalationSweepStillLetsTheCompletionSweepRun() {
        doThrow(new IllegalStateException("no such flow"))
                .when(subFlowReconciler).escalateParentsOfTerminatedChildren();

        poller.maintain();

        verify(claimService).reapStale(DEFAULT_LEASE_TIMEOUT_SECONDS);
        verify(subFlowReconciler).reconcileCompletedChildren();
    }
}
