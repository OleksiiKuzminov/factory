package org.folio.factory.app.web.dashboard;

import org.folio.factory.app.web.dashboard.DashboardStats.ConnectorOutcome;
import org.folio.factory.app.web.dashboard.DashboardStats.DailyCount;
import org.folio.factory.app.web.dashboard.DashboardStats.FlowStatusCount;
import org.folio.factory.app.web.dashboard.DashboardStats.HitlStats;
import org.folio.factory.app.web.dashboard.DashboardStats.StatusCount;
import org.folio.factory.app.web.dashboard.DashboardStats.StepFailureCount;
import org.folio.factory.app.web.dashboard.DashboardStats.StepTokenCount;
import org.folio.factory.app.web.dashboard.DashboardStats.TokenUsage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates execution/audit/HITL metrics for the dashboard charts in one payload.
 * Uses native Postgres SQL through {@link JdbcClient}: {@code date_trunc},
 * {@code FILTER (WHERE ...)} and {@code detail->>'connector'} are inexpressible in
 * JPQL, and the project is Postgres-only.
 *
 * <p>Every entry point serves a snapshot cached per window for
 * {@code factory.dashboard.stats-cache-ttl}: the aggregates are unwindowed group-bys
 * over {@code pipeline_execution} and scans of the append-only {@code audit_event}
 * table (which retention never purges), so re-running them per dashboard load — and
 * per 30s chart poll, per viewer — does not scale with the ledger.</p>
 */
@Service
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardStatsService {

    /** The windows the console offers; anything else is refused so the cache stays bounded. */
    public static final List<Integer> ALLOWED_WINDOWS = List.of(1, 7, 14, 30, 90);

    private final JdbcClient jdbc;
    private final long cacheTtlNanos;
    private final Map<Integer, Snapshot> cached = new ConcurrentHashMap<>();

    public DashboardStatsService(JdbcClient jdbc, DashboardProperties properties) {
        this.jdbc = jdbc;
        this.cacheTtlNanos = properties.statsCacheTtl().toNanos();
    }

    /**
     * The cached snapshot for {@code days}, refreshed once its TTL has passed. Each
     * window keeps its own entry, so two viewers on different ranges neither evict
     * each other nor ever see the other's numbers. Deliberately unsynchronised: a
     * refresh racing another merely aggregates twice, whereas a lock would queue
     * every dashboard request behind one slow scan. A refresh that throws stores
     * nothing, so the next call retries instead of caching the failure.
     *
     * @throws IllegalArgumentException if {@code days} is not one of {@link #ALLOWED_WINDOWS}
     */
    public DashboardStats compute(int days) {
        if (!ALLOWED_WINDOWS.contains(days)) {
            throw new IllegalArgumentException("days must be one of " + ALLOWED_WINDOWS);
        }
        if (cacheTtlNanos <= 0) {
            return aggregate(days);
        }
        Snapshot current = cached.get(days);
        if (current != null && System.nanoTime() - current.expiresAtNanos() < 0) {
            return current.stats();
        }
        DashboardStats fresh = aggregate(days);
        cached.put(days, new Snapshot(fresh, System.nanoTime() + cacheTtlNanos));
        return fresh;
    }

    /** Window totals for the token KPI card; the UI renders these without the rest of the payload. */
    public TokenUsage tokenUsage(int days) {
        return compute(days).tokens();
    }

    /** Per-flow, per-step token spend, heaviest first. */
    public List<StepTokenCount> stepTokens(int days) {
        return compute(days).stepTokens();
    }

    private DashboardStats aggregate(int days) {
        OffsetDateTime since = since(days);

        PendingRow pending = pending();
        DecidedRow decided = decided(since);
        HitlStats hitl = new HitlStats(pending.count(), pending.oldestSeconds(),
                decided.count(), decided.avgSeconds());

        return new DashboardStats(Instant.now(), days,
                executionsByStatus(), executionsByFlowAndStatus(), executionsPerDay(since),
                stepFailures(since), hitl, connectorOutcomes(since),
                tokenUsage(since), stepTokens(since));
    }

    private static OffsetDateTime since(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);
    }

    // Steps that report no usage (HITL gates, deterministic workers) store '{}', so
    // detail->>'promptTokens' is SQL NULL there and sum() skips it; coalesce keeps an
    // all-empty window at 0 rather than null.
    private TokenUsage tokenUsage(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT coalesce(sum((detail->>'promptTokens')::bigint), 0) AS prompt,
                               coalesce(sum((detail->>'completionTokens')::bigint), 0) AS completion
                        FROM audit_event
                        WHERE event_type = 'STEP_COMPLETED' AND occurred_at >= :since
                        """)
                .param("since", since)
                .query((rs, n) -> new TokenUsage(rs.getLong("prompt"), rs.getLong("completion")))
                .single();
    }

    private List<StepTokenCount> stepTokens(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT coalesce(e.flow_id, '(purged)') AS flow_id, a.step_id,
                               coalesce(sum((a.detail->>'promptTokens')::bigint), 0) AS prompt,
                               coalesce(sum((a.detail->>'completionTokens')::bigint), 0) AS completion
                        FROM audit_event a LEFT JOIN pipeline_execution e ON e.id = a.execution_id
                        WHERE a.event_type = 'STEP_COMPLETED' AND a.occurred_at >= :since
                        -- jsonb_exists, not the `?` operator: JDBC would bind `?` as a parameter.
                          AND a.step_id IS NOT NULL AND jsonb_exists(a.detail, 'promptTokens')
                        -- Output aliases are only usable bare in ORDER BY, not inside an
                        -- expression, so the sum is repeated rather than referenced.
                        GROUP BY 1, 2
                        ORDER BY coalesce(sum((a.detail->>'promptTokens')::bigint), 0)
                                 + coalesce(sum((a.detail->>'completionTokens')::bigint), 0) DESC
                        """)
                .param("since", since)
                .query((rs, n) -> new StepTokenCount(rs.getString("flow_id"), rs.getString("step_id"),
                        rs.getLong("prompt"), rs.getLong("completion")))
                .list();
    }

    private List<StatusCount> executionsByStatus() {
        return jdbc.sql("SELECT status, count(*) AS c FROM pipeline_execution GROUP BY status")
                .query((rs, n) -> new StatusCount(rs.getString("status"), rs.getLong("c")))
                .list();
    }

    private List<FlowStatusCount> executionsByFlowAndStatus() {
        return jdbc.sql("""
                        SELECT flow_id, status, count(*) AS c
                        FROM pipeline_execution GROUP BY flow_id, status ORDER BY flow_id
                        """)
                .query((rs, n) -> new FlowStatusCount(rs.getString("flow_id"), rs.getString("status"), rs.getLong("c")))
                .list();
    }

    private List<DailyCount> executionsPerDay(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT (date_trunc('day', created_at AT TIME ZONE 'UTC'))::date AS day, status, count(*) AS c
                        FROM pipeline_execution WHERE created_at >= :since GROUP BY 1, 2 ORDER BY 1
                        """)
                .param("since", since)
                .query((rs, n) -> new DailyCount(rs.getObject("day", LocalDate.class),
                        rs.getString("status"), rs.getLong("c")))
                .list();
    }

    private List<StepFailureCount> stepFailures(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT coalesce(e.flow_id, '(purged)') AS flow_id, a.step_id,
                               count(*) FILTER (WHERE a.event_type = 'STEP_FAILED') AS failures,
                               count(*) FILTER (WHERE a.event_type = 'RETRY_SCHEDULED') AS retries
                        FROM audit_event a LEFT JOIN pipeline_execution e ON e.id = a.execution_id
                        WHERE a.event_type IN ('STEP_FAILED', 'RETRY_SCHEDULED') AND a.occurred_at >= :since
                          AND a.step_id IS NOT NULL
                        GROUP BY 1, 2 ORDER BY failures DESC
                        """)
                .param("since", since)
                .query((rs, n) -> new StepFailureCount(rs.getString("flow_id"), rs.getString("step_id"),
                        rs.getLong("failures"), rs.getLong("retries")))
                .list();
    }

    private List<ConnectorOutcome> connectorOutcomes(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT detail->>'connector' AS connector, event_type, count(*) AS c
                        FROM audit_event
                        WHERE event_type IN ('CONNECTOR_ACTION', 'CONNECTOR_SKIPPED', 'CONNECTOR_FAILED')
                          AND occurred_at >= :since
                        GROUP BY 1, 2
                        """)
                .param("since", since)
                .query((rs, n) -> new ConnectorOutcome(rs.getString("connector"),
                        rs.getString("event_type"), rs.getLong("c")))
                .list();
    }

    private PendingRow pending() {
        return jdbc.sql("""
                        SELECT count(*) AS c,
                               extract(epoch FROM (now() - min(created_at)))::double precision AS oldest
                        FROM hitl_review WHERE status = 'PENDING'
                        """)
                .query((rs, n) -> new PendingRow(rs.getLong("c"), (Double) rs.getObject("oldest")))
                .single();
    }

    private DecidedRow decided(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT count(*) AS c,
                               avg(extract(epoch FROM decided_at - created_at))::double precision AS avg_s
                        FROM hitl_review WHERE decided_at IS NOT NULL AND decided_at >= :since
                        """)
                .param("since", since)
                .query((rs, n) -> new DecidedRow(rs.getLong("c"), (Double) rs.getObject("avg_s")))
                .single();
    }

    private record Snapshot(DashboardStats stats, long expiresAtNanos) {
    }

    private record PendingRow(long count, Double oldestSeconds) {
    }

    private record DecidedRow(long count, Double avgSeconds) {
    }
}
