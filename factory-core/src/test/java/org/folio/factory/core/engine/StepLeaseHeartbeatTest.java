package org.folio.factory.core.engine;

import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mockingDetails;

/**
 * Lease refresh timing in isolation: the cadence, the stop on close, and the hard
 * budget that keeps a wedged step reapable.
 */
@ExtendWith(MockitoExtension.class)
class StepLeaseHeartbeatTest {

    @Mock
    StateManager stateManager;

    StepLeaseHeartbeat heartbeat;

    final UUID executionId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        if (heartbeat != null) {
            heartbeat.stop();
        }
    }

    private StepLeaseHeartbeat heartbeatWith(long intervalSeconds, long maxSeconds) {
        heartbeat = new StepLeaseHeartbeat(stateManager, new EngineProperties(
                null, null, null, null, null, intervalSeconds, maxSeconds, null, null));
        return heartbeat;
    }

    private int refreshes() {
        return mockingDetails(stateManager).getInvocations().size();
    }

    @Test
    void theLeaseIsRefreshedRepeatedlyWhileTheStepRuns() throws Exception {
        try (AutoCloseable lease = heartbeatWith(1, 60).start(executionId)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(refreshes()).isGreaterThanOrEqualTo(2));
        }
    }

    @Test
    void refreshingStopsWhenTheStepHandleIsClosed() throws Exception {
        AutoCloseable lease = heartbeatWith(1, 60).start(executionId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(refreshes()).isGreaterThanOrEqualTo(1));
        lease.close();

        Thread.sleep(200);
        int atClose = refreshes();
        Thread.sleep(1_500);

        assertThat(refreshes()).isEqualTo(atClose);
    }

    /**
     * Holding the lease's monitor pins the exact race close() has to win: the scheduler
     * dispatches a refresh, which parks on the monitor until the closing thread lets it
     * go. Cancelling the future cannot stop a task that already started, so only an
     * explicit closed flag keeps that straggler from writing a heartbeat after close()
     * returned — colliding on @Version with the engine's next write to the same row.
     */
    @Test
    void aRefreshAlreadyDispatchedWhenTheHandleClosesNeverWrites() throws Exception {
        AutoCloseable lease = heartbeatWith(1, 60).start(executionId);

        synchronized (lease) {
            Thread.sleep(2_000);
            lease.close();
        }
        Thread.sleep(1_000);

        assertThat(refreshes()).isZero();
    }

    @Test
    void refreshingStopsOnceTheStepOutlivesTheHeartbeatBudget() throws Exception {
        // Budget of 2s at a 1s cadence: one refresh, then the lease is left to go stale
        // so a step wedged on a hung socket stays reapable.
        try (AutoCloseable lease = heartbeatWith(1, 2).start(executionId)) {
            Thread.sleep(2_500);
            int atBudget = refreshes();
            Thread.sleep(2_500);

            assertThat(refreshes()).isEqualTo(atBudget);
            assertThat(atBudget).isLessThanOrEqualTo(2);
        }
    }
}
