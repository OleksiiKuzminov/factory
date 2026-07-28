package org.folio.factory.core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives the engine: claims runnable executions from the database on a fixed
 * cadence and dispatches them to the worker pool. Restart-safe by construction —
 * all queue state lives in Postgres.
 */
@Component
@ConditionalOnProperty(prefix = "factory.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionPoller {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPoller.class);

    private final ExecutionClaimService claimService;
    private final ExecutionEngine engine;
    private final SubFlowReconciler subFlowReconciler;
    private final EngineProperties properties;
    private final AsyncTaskExecutor executor;

    // Flipped at context close so the scheduled poll stops claiming NEW work while
    // in-flight steps drain. volatile: written on the shutdown thread that publishes
    // ContextClosedEvent, read on the scheduler thread that runs poll().
    private volatile boolean shuttingDown = false;

    // The one-time startup lease reclaim runs on the first poll, before the first
    // claim, on the single scheduler thread — so it needs no cross-thread guard.
    private boolean startupReclaimDone = false;

    public ExecutionPoller(ExecutionClaimService claimService, ExecutionEngine engine,
                           SubFlowReconciler subFlowReconciler, EngineProperties properties,
                           AsyncTaskExecutor factoryEngineExecutor) {
        this.claimService = claimService;
        this.engine = engine;
        this.subFlowReconciler = subFlowReconciler;
        this.properties = properties;
        this.executor = factoryEngineExecutor;
    }

    // ContextClosedEvent is published at the very start of context close, before the
    // web-server graceful-shutdown phase and before the scheduler is stopped, so this
    // is the earliest safe point to stop pulling new work.
    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        shuttingDown = true;
    }

    @Scheduled(fixedDelayString = "${factory.engine.poll-interval-ms:2000}")
    public void poll() {
        if (shuttingDown) {
            // Stop claiming new executions so the worker pool drains without new work
            // piling up; already-dispatched tasks finish under the executor's
            // await-termination window. No claim() here means no RUNNING rows are
            // created that we would then have to release.
            return;
        }
        if (!startupReclaimDone) {
            // Once, before the first claim: recover RUNNING rows orphaned by a prior
            // crash so they neither sit idle until the lease reaper nor consume
            // concurrency-cap slots. Single-instance only (see EngineProperties).
            if (properties.reclaimRunningOnStartup()) {
                int reclaimed = claimService.reclaimOrphanedRunning();
                if (reclaimed > 0) {
                    log.warn("Startup reclaim: requeued {} orphaned RUNNING execution(s) from a prior process", reclaimed);
                }
            }
            startupReclaimDone = true;
        }
        for (UUID executionId : claimService.claim(properties.batchSize())) {
            try {
                executor.execute(() -> engine.advance(executionId));
            } catch (RuntimeException e) {
                // Submission failed (e.g. executor shutting down): give the claim
                // back immediately instead of leaving it RUNNING for the reaper.
                claimService.release(executionId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${factory.engine.reap-interval-ms:30000}")
    public void maintain() {
        // Each recovery independently: they address unrelated stalls, so a failing one
        // must not suppress the others — and a throwing @Scheduled method would also
        // skip everything sequenced after it in the same pass.
        sweep("lease reap", () -> claimService.reapStale(properties.leaseTimeoutSeconds()));
        sweep("sub-flow escalation", subFlowReconciler::escalateParentsOfTerminatedChildren);
        sweep("sub-flow completion", subFlowReconciler::reconcileCompletedChildren);
    }

    private void sweep(String name, Runnable operation) {
        try {
            operation.run();
        } catch (Exception e) {
            log.error("Maintenance sweep '{}' failed; the remaining sweeps still ran", name, e);
        }
    }
}
