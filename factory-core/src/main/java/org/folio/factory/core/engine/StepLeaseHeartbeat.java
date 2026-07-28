package org.folio.factory.core.engine;

import jakarta.annotation.PreDestroy;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the lease of an in-flight step fresh so the poller's reaper does not requeue
 * a step that is merely slow. Without it, any step outliving
 * {@code lease-timeout-seconds} is driven a SECOND time concurrently: the guarded
 * step cursor stops the duplicate driver from skipping steps, but nothing un-does the
 * worker's external side effects — a re-driven finalizer pushes to GitHub, Jira and
 * TestRail twice.
 */
@Component
public class StepLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(StepLeaseHeartbeat.class);

    private final StateManager stateManager;
    private final EngineProperties properties;
    private final ScheduledExecutorService scheduler;

    public StepLeaseHeartbeat(StateManager stateManager, EngineProperties properties) {
        this.stateManager = stateManager;
        this.properties = properties;
        // Daemon: refreshing a lease must never hold up JVM exit. One thread is enough —
        // each refresh is a single short UPDATE.
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "factory-step-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Refreshes {@code executionId}'s lease every {@code heartbeat-interval-seconds}
     * until the returned handle is closed.
     *
     * <p>Refreshing also stops after {@code max-step-heartbeat-seconds} even when the
     * handle is never closed: a step wedged on a hung socket must remain reapable, or
     * the heartbeat converts a recoverable stall into a permanent one.</p>
     */
    public AutoCloseable start(UUID executionId) {
        return new Lease(executionId);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    private final class Lease implements AutoCloseable {

        private final UUID executionId;
        private final long deadlineNanos;
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private boolean closed;

        private Lease(UUID executionId) {
            this.executionId = executionId;
            this.deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(properties.maxStepHeartbeatSeconds());
            long interval = properties.heartbeatIntervalSeconds();
            scheduled.set(scheduler.scheduleWithFixedDelay(this::refresh, interval, interval, TimeUnit.SECONDS));
        }

        // Synchronized with cancel(), and re-checking `closed` after taking the monitor,
        // so no refresh can write once close() has returned. cancel(false) alone is not
        // enough: a refresh already dispatched by the scheduler and merely waiting on
        // this monitor still runs afterwards, and its write to the execution row would
        // collide on @Version with the engine's immediately-following advanceStep —
        // failing the step, which then retries and repeats the very side effects this
        // class exists to protect.
        private synchronized void refresh() {
            if (closed) {
                return;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                log.warn("Stopped refreshing the lease of execution {} after {}s; if its step is wedged "
                        + "the reaper will now recover it", executionId, properties.maxStepHeartbeatSeconds());
                cancel();
                return;
            }
            try {
                stateManager.heartbeat(executionId);
            } catch (Exception e) {
                // A missed refresh only risks an early reap; failing the step over it
                // would be strictly worse.
                log.warn("Could not refresh the lease of execution {}", executionId, e);
            }
        }

        @Override
        public void close() {
            cancel();
        }

        private synchronized void cancel() {
            closed = true;
            ScheduledFuture<?> task = scheduled.getAndSet(null);
            if (task != null) {
                // cancel(false): a refresh holding the monitor must finish its short
                // UPDATE rather than be interrupted mid-transaction; `closed` stops the
                // ones that have not taken the monitor yet.
                task.cancel(false);
            }
        }
    }
}
