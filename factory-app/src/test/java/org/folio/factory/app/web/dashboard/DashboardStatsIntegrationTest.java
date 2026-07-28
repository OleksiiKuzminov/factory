package org.folio.factory.app.web.dashboard;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Exercises the native Postgres aggregation in {@link DashboardStatsService}
 * against a real database with data seeded through the production services, and
 * asserts the JSON shape the dashboard charts depend on. The service SQL uses
 * {@code date_trunc}, {@code FILTER (WHERE ...)} and {@code detail->>'connector'}
 * that no unit test with a mocked repository can validate.
 *
 * <p>The autowired service runs with caching off so the aggregation assertions see
 * the database rather than a snapshot; the caching behaviour itself is exercised by
 * the methods below, each against its own service instance and TTL.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false",
                "factory.auth.enabled=false", "factory.dashboard.stats-cache-ttl=0s"})
@Import(StubLlmConfiguration.class)
@Testcontainers
class DashboardStatsIntegrationTest {

    private static final String FLOW_A = "test-factory";
    private static final String FLOW_B = "release-pipeline";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    @Autowired
    StateManager stateManager;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    DashboardStatsService dashboardStatsService;

    @Autowired
    JdbcClient jdbc;

    private RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // The only test that seeds: the exact-count assertions below depend on seeding
    // running exactly once, and this class shares one Spring context / database
    // across methods, so a second @Test that re-seeds would double the counts. The
    // caching tests below therefore assert on snapshot identity and query counts,
    // never on row counts.
    @Test
    void aggregatesSeededDataAndSerializesDayAsIsoDateOverHttp() {
        UUID backdatedId = seed();

        // Derive "today" from the seeded rows' own created_at, not wall-clock now:
        // a run seeded just before midnight UTC must not fail because the assertion
        // computes LocalDate.now() just after midnight.
        LocalDate seededDay = jdbc.sql("""
                        SELECT (date_trunc('day', created_at AT TIME ZONE 'UTC'))::date AS day
                        FROM pipeline_execution WHERE id <> :backdated ORDER BY created_at DESC LIMIT 1
                        """)
                .param("backdated", backdatedId)
                .query(LocalDate.class).single();

        DashboardStats stats = dashboardStatsService.compute(7);

        assertThat(stats.executionsByStatus())
                .anySatisfy(s -> assertThat(s.status()).isEqualTo(ExecutionStatus.COMPLETED.name()))
                .anySatisfy(s -> assertThat(s.status()).isEqualTo(ExecutionStatus.RUNNING.name()));

        assertThat(stats.executionsByFlowAndStatus())
                .anySatisfy(s -> {
                    assertThat(s.flowId()).isEqualTo(FLOW_B);
                    assertThat(s.status()).isEqualTo(ExecutionStatus.PENDING.name());
                });

        assertThat(stats.executionsPerDay())
                .as("the seeded bucket must be present in the 7-day window")
                .anySatisfy(d -> assertThat(d.day()).isEqualTo(seededDay));
        assertThat(stats.executionsPerDay())
                .as("the 10-day-old execution must be excluded from the 7-day window")
                .noneSatisfy(d -> assertThat(d.day()).isEqualTo(seededDay.minusDays(10)));
        assertThat(stats.stepFailures())
                .as("the 10-day-old step failure must be excluded from the 7-day window")
                .noneSatisfy(f -> assertThat(f.stepId()).isEqualTo("legacy-step"));

        assertThat(stats.stepFailures())
                .anySatisfy(f -> {
                    assertThat(f.stepId()).isEqualTo("triage");
                    assertThat(f.failures()).isEqualTo(1);
                    assertThat(f.retriesScheduled()).isEqualTo(1);
                });

        assertThat(stats.connectorOutcomes())
                .as("an attempted-and-failed sync stays distinguishable from a successful one")
                .extracting(DashboardStats.ConnectorOutcome::connector,
                        DashboardStats.ConnectorOutcome::eventType,
                        DashboardStats.ConnectorOutcome::count)
                .contains(tuple("github", AuditEventType.CONNECTOR_ACTION.name(), 1L),
                        tuple("jira", AuditEventType.CONNECTOR_SKIPPED.name(), 1L),
                        tuple("testrail", AuditEventType.CONNECTOR_FAILED.name(), 1L));

        assertThat(stats.hitl().pending()).isEqualTo(1);
        assertThat(stats.hitl().decidedInWindow()).isEqualTo(1);

        // 734 + 810 prompt, 110 + 660 completion; the '{}' completion adds nothing.
        assertThat(stats.tokens().promptTokens()).isEqualTo(1544);
        assertThat(stats.tokens().completionTokens()).isEqualTo(770);
        assertThat(stats.tokens().totalTokens()).isEqualTo(2314);

        assertThat(stats.stepTokens())
                .as("ordered heaviest first, and the zero-usage step is omitted entirely")
                .extracting(DashboardStats.StepTokenCount::stepId)
                .containsExactly("test-spec", "triage");
        assertThat(stats.stepTokens().get(0).flowId()).isEqualTo(FLOW_A);
        assertThat(stats.stepTokens().get(0).totalTokens()).isEqualTo(1470);

        // Widening the window to 30 days pulls the 10-day-old rows in.
        DashboardStats stats30 = dashboardStatsService.compute(30);
        assertThat(stats30.executionsPerDay())
                .as("the 10-day-old execution is included in the 30-day window")
                .anySatisfy(d -> assertThat(d.day()).isEqualTo(seededDay.minusDays(10)));
        assertThat(stats30.stepFailures())
                .as("the 10-day-old step failure is included in the 30-day window")
                .anySatisfy(f -> assertThat(f.stepId()).isEqualTo("legacy-step"));

        // Same data over HTTP: charts.js string-joins on this exact shape
        // (r.day + '|' + r.status), so the day must serialize as a plain
        // "yyyy-MM-dd" string, not a [y,m,d] array.
        JsonNode body = rest.get().uri("/api/dashboard/stats?days=7").retrieve().body(JsonNode.class);
        JsonNode perDay = body.path("executionsPerDay");
        assertThat(perDay.isArray()).isTrue();
        assertThat(perDay).isNotEmpty();
        for (JsonNode row : perDay) {
            assertThat(row.path("day").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    @Test
    void aSecondCallInsideTheTtlIsServedFromTheSnapshotWithoutQueryingAgain() {
        AtomicInteger queries = new AtomicInteger();
        DashboardStatsService service = serviceWithTtl(counting(queries), Duration.ofSeconds(30));

        DashboardStats first = service.compute(7);
        int afterFirst = queries.get();
        DashboardStats second = service.compute(7);

        assertThat(afterFirst).isPositive();
        assertThat(second).isSameAs(first);
        assertThat(queries.get()).isEqualTo(afterFirst);
    }

    @Test
    void theSnapshotIsRecomputedOnceItsTtlHasPassed() throws InterruptedException {
        AtomicInteger queries = new AtomicInteger();
        DashboardStatsService service = serviceWithTtl(counting(queries), Duration.ofMillis(100));

        DashboardStats first = service.compute(7);
        int afterFirst = queries.get();
        Thread.sleep(150);

        assertThat(service.compute(7)).isNotSameAs(first);
        assertThat(queries.get()).isGreaterThan(afterFirst);
    }

    @Test
    void aZeroTtlDisablesCachingSoEveryCallReRunsTheAggregates() {
        AtomicInteger queries = new AtomicInteger();
        DashboardStatsService service = serviceWithTtl(counting(queries), Duration.ZERO);

        DashboardStats first = service.compute(7);
        int afterFirst = queries.get();

        assertThat(service.compute(7)).isNotSameAs(first);
        assertThat(queries.get()).isEqualTo(afterFirst * 2);
    }

    @Test
    void oneDayWindowNeverServesAnotherWindowsSnapshot() {
        DashboardStatsService service = serviceWithTtl(jdbc, Duration.ofSeconds(30));

        DashboardStats sevenDay = service.compute(7);
        DashboardStats thirtyDay = service.compute(30);

        assertThat(sevenDay.days()).isEqualTo(7);
        assertThat(thirtyDay.days()).isEqualTo(30);
        assertThat(thirtyDay).isNotSameAs(sevenDay);
        assertThat(service.compute(7)).isNotSameAs(thirtyDay)
                .extracting(DashboardStats::days).isEqualTo(7);
    }

    /**
     * The dashboard offers five windows and polls every 30s, so two viewers on
     * different ranges hit the service alternately. With one cache slot each request
     * would evict the other's snapshot and re-run every aggregate.
     */
    @Test
    void windowsAreCachedSideBySideSoAlternatingViewersDoNotEvictEachOther() {
        AtomicInteger queries = new AtomicInteger();
        DashboardStatsService service = serviceWithTtl(counting(queries), Duration.ofSeconds(30));

        DashboardStats sevenDay = service.compute(7);
        DashboardStats thirtyDay = service.compute(30);
        int afterBoth = queries.get();

        assertThat(service.compute(7)).isSameAs(sevenDay);
        assertThat(service.compute(30)).isSameAs(thirtyDay);
        assertThat(queries.get()).as("both windows stayed cached").isEqualTo(afterBoth);
    }

    @Test
    void anUnlistedWindowIsRejectedBeforeAnyQuerySoTheCacheKeySpaceStaysBounded() {
        AtomicInteger queries = new AtomicInteger();
        DashboardStatsService service = serviceWithTtl(counting(queries), Duration.ofSeconds(30));

        assertThatThrownBy(() -> service.compute(13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[1, 7, 14, 30, 90]");
        assertThat(queries.get()).isZero();

        Integer status = rest.get().uri("/api/dashboard/stats?days=13")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).as("the REST endpoint surfaces the same constraint").isEqualTo(422);
    }

    /**
     * A snapshot is shared by every viewer inside the TTL, so the lists it hands out
     * must not be the mutable ones {@code JdbcClient} returns.
     */
    @Test
    void servedListsAreImmutableSoOneViewerCannotCorruptTheCachedSnapshot() {
        DashboardStats stats = serviceWithTtl(jdbc, Duration.ofSeconds(30)).compute(7);

        assertThatThrownBy(() -> stats.executionsByStatus().add(new DashboardStats.StatusCount("HACKED", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> stats.stepTokens().add(new DashboardStats.StepTokenCount("f", "s", 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRefreshThatThrowsIsNotCachedSoTheNextCallRecovers() throws InterruptedException {
        AtomicBoolean broken = new AtomicBoolean();
        JdbcClient flaky = sql -> {
            if (broken.get()) {
                throw new DataAccessResourceFailureException("database unavailable");
            }
            return jdbc.sql(sql);
        };
        DashboardStatsService service = serviceWithTtl(flaky, Duration.ofMillis(100));

        DashboardStats first = service.compute(7);
        broken.set(true);
        Thread.sleep(150);

        assertThatThrownBy(() -> service.compute(7)).isInstanceOf(DataAccessResourceFailureException.class);

        broken.set(false);
        assertThat(service.compute(7)).isNotSameAs(first);
    }

    @Test
    void theTokenKpiAccessorsReadTheCachedSnapshotRatherThanRequerying() {
        AtomicInteger queries = new AtomicInteger();
        DashboardStatsService service = serviceWithTtl(counting(queries), Duration.ofSeconds(30));

        DashboardStats snapshot = service.compute(7);
        int afterCompute = queries.get();

        assertThat(service.tokenUsage(7)).isEqualTo(snapshot.tokens());
        assertThat(service.stepTokens(7)).isSameAs(snapshot.stepTokens());
        assertThat(queries.get()).isEqualTo(afterCompute);
    }

    private DashboardStatsService serviceWithTtl(JdbcClient client, Duration ttl) {
        return new DashboardStatsService(client, new DashboardProperties(ttl));
    }

    /** JdbcClient is a functional interface, so counting statements needs no mocking framework. */
    private JdbcClient counting(AtomicInteger queries) {
        return sql -> {
            queries.incrementAndGet();
            return jdbc.sql(sql);
        };
    }

    private UUID seed() {
        PipelineExecution completed = stateManager.createExecution(FLOW_A, "1", "{}");

        PipelineExecution running = stateManager.createExecution(FLOW_A, "1", "{}");
        stateManager.transition(running.getId(), ExecutionStatus.RUNNING, Map.of());

        stateManager.createExecution(FLOW_B, "1", "{}"); // stays PENDING

        PipelineExecution rejected = stateManager.createExecution(FLOW_B, "1", "{}");
        stateManager.transition(rejected.getId(), ExecutionStatus.REJECTED, Map.of());

        auditLog.record(running.getId(), AuditEventType.STEP_FAILED, "triage",
                Map.of("error", "boom"));
        auditLog.record(running.getId(), AuditEventType.RETRY_SCHEDULED, "triage",
                Map.of("attempt", 1));
        auditLog.record(completed.getId(), AuditEventType.CONNECTOR_ACTION, "finalize",
                Map.of("connector", "github", "action", "open-pr"));
        auditLog.record(completed.getId(), AuditEventType.CONNECTOR_SKIPPED, "finalize",
                Map.of("connector", "jira", "reason", "unconfigured"));
        auditLog.record(completed.getId(), AuditEventType.CONNECTOR_FAILED, "finalize",
                Map.of("connector", "testrail", "reason", "500 from TestRail"));

        // Token-bearing completions plus one that reported nothing: the '{}' row must
        // contribute zero to the totals and produce no breakdown row at all.
        auditLog.record(completed.getId(), AuditEventType.STEP_COMPLETED, "triage",
                Map.of("promptTokens", 734, "completionTokens", 110));
        auditLog.record(completed.getId(), AuditEventType.STEP_COMPLETED, "test-spec",
                Map.of("promptTokens", 810, "completionTokens", 660));
        auditLog.record(completed.getId(), AuditEventType.STEP_COMPLETED, "finalize", Map.of());

        reviews.save(new HitlReview(running.getId(), "gate-1-test-plan", 2, "{}"));
        HitlReview decided = new HitlReview(completed.getId(), "gate-2-signoff", 5, "{}");
        decided.decide(HitlReviewStatus.AMENDED, "AMEND", "qa-lead", "looks good", null);
        reviews.save(decided);

        stateManager.transition(completed.getId(), ExecutionStatus.COMPLETED, Map.of());

        // A 10-day-old execution and audit row: excluded from a 7-day window but
        // counted all-time. The audit_event table is append-only (UPDATE/DELETE are
        // blocked by a trigger), so the row is inserted already-backdated.
        PipelineExecution old = stateManager.createExecution(FLOW_B, "1", "{}");
        jdbc.sql("UPDATE pipeline_execution SET created_at = now() - interval '10 days' WHERE id = :id")
                .param("id", old.getId()).update();
        jdbc.sql("""
                        INSERT INTO audit_event (execution_id, event_type, step_id, actor, detail, occurred_at)
                        VALUES (:id, 'STEP_FAILED', 'legacy-step', 'system', '{}'::jsonb, now() - interval '10 days')
                        """)
                .param("id", old.getId()).update();
        return old.getId();
    }
}
