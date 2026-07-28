package org.folio.factory.core.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.engine")
public record EngineProperties(
        Boolean enabled,
        Long pollIntervalMs,
        Integer batchSize,
        Integer workerThreads,
        Long leaseTimeoutSeconds,
        Long heartbeatIntervalSeconds,
        Long maxStepHeartbeatSeconds,
        Integer shutdownAwaitSeconds,
        Boolean reclaimRunningOnStartup) {

    public EngineProperties {
        enabled = enabled == null || enabled;
        pollIntervalMs = pollIntervalMs == null ? 2000L : pollIntervalMs;
        batchSize = batchSize == null ? 5 : batchSize;
        workerThreads = workerThreads == null ? 4 : workerThreads;
        // Must exceed the longest legitimate single step (Karate runs are capped
        // at 15 minutes) or the reaper re-queues live executions.
        leaseTimeoutSeconds = leaseTimeoutSeconds == null ? 1800L : leaseTimeoutSeconds;
        // Cadence at which a running step refreshes its own lease, so a merely slow
        // step is never reaped and driven a second time. Floored at 1s: a zero delay
        // would spin the heartbeat thread.
        heartbeatIntervalSeconds = heartbeatIntervalSeconds == null ? 60L : Math.max(1L, heartbeatIntervalSeconds);
        // Ceiling on that defence. Past it the engine stops refreshing and lets the
        // reaper recover the execution — a step wedged on a hung socket must stay
        // reapable, or the heartbeat turns a recoverable stall into a permanent one.
        // Must exceed the slowest legitimate step (LLM read timeout, Karate's 15-min cap).
        maxStepHeartbeatSeconds = maxStepHeartbeatSeconds == null ? 3600L : maxStepHeartbeatSeconds;
        // Bounded drain budget on shutdown; a step still running past it is
        // abandoned at forced pool shutdown and recovered by the reaper after
        // leaseTimeoutSeconds. Non-null by construction (used as int below).
        shutdownAwaitSeconds = shutdownAwaitSeconds == null ? 30 : shutdownAwaitSeconds;
        // On startup, requeue any RUNNING rows left behind by a prior process so a
        // crash does not strand work (and, with the concurrency cap on, does not tie
        // up cap slots) until the lease reaper clears them. SINGLE-INSTANCE ASSUMPTION:
        // at startup no execution can legitimately be RUNNING for this process. In a
        // multi-instance deployment set this false so one instance's restart never
        // requeues a peer's live work.
        reclaimRunningOnStartup = reclaimRunningOnStartup == null || reclaimRunningOnStartup;
    }
}
