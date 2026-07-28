package org.folio.factory.core.engine;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The reaper and a running step, racing for real. A step slower than the lease must
 * keep its claim — being reaped means a second driver runs the same worker, and the
 * guarded step cursor cannot un-do that worker's external side effects. A step that
 * outlives the heartbeat budget must lose the claim anyway, or a wedged worker would
 * defend its lease forever. And a finished step must release its handle, or the leak
 * is invisible: nothing else observes a lease that keeps refreshing.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "factory.engine.lease-timeout-seconds=2",
        "factory.engine.heartbeat-interval-seconds=1",
        "factory.engine.max-step-heartbeat-seconds=4"
})
@Testcontainers
class StepLeaseHeartbeatIntegrationTest {

    private static final long LEASE_TIMEOUT_SECONDS = 2;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ExecutionEngine engine;

    @Autowired
    ExecutionClaimService claimService;

    @Autowired
    StateManager stateManager;

    private Thread drive(PipelineExecution execution) {
        stateManager.transition(execution.getId(), ExecutionStatus.RUNNING, null);
        Thread driver = new Thread(() -> engine.advance(execution.getId()), "test-driver");
        driver.start();
        return driver;
    }

    @Test
    void aStepSlowerThanTheLeaseKeepsItsClaimAndIsNotReaped() throws Exception {
        // fake-slow sleeps 3s under a 2s lease: without the heartbeat the reaper wins
        // at 2s and the step is driven a second time.
        PipelineExecution execution = stateManager.createExecution("fake-slow", "1.0.0", null);
        Thread driver = drive(execution);

        while (driver.isAlive()) {
            claimService.reapStale(LEASE_TIMEOUT_SECONDS);
            assertThat(stateManager.get(execution.getId()).getStatus()).isNotEqualTo(ExecutionStatus.PENDING);
            Thread.sleep(200);
        }
        driver.join();

        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void theLeaseStopsBeingDefendedOnceTheStepIsDone() throws Exception {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        drive(execution).join(Duration.ofSeconds(30).toMillis());
        assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        Instant settled = stateManager.get(execution.getId()).getUpdatedAt();

        // Two heartbeat intervals, still inside the 4s budget: a handle the engine
        // failed to close keeps refreshing here and moves updated_at.
        Thread.sleep(2_500);

        assertThat(stateManager.get(execution.getId()).getUpdatedAt())
                .describedAs("a leaked lease keeps writing to an execution that has already finished")
                .isEqualTo(settled);
    }

    @Test
    void aStepOutlivingTheHeartbeatBudgetBecomesReapableAgain() throws Exception {
        // fake-wedged sleeps 8s: refreshes stop at 4s, the 2s lease goes stale by ~6s.
        PipelineExecution execution = stateManager.createExecution("fake-wedged", "1.0.0", null);
        Thread driver = drive(execution);
        try {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                claimService.reapStale(LEASE_TIMEOUT_SECONDS);
                assertThat(stateManager.get(execution.getId()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
            });

            assertThat(driver.isAlive())
                    .describedAs("the step is still running, so the reap really happened mid-step")
                    .isTrue();
        } finally {
            driver.join(Duration.ofSeconds(20).toMillis());
        }
    }
}
