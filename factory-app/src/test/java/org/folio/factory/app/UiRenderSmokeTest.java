package org.folio.factory.app;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that actually renders the Thymeleaf templates end to end;
 * standalone MockMvc controller tests never resolve the layout/component
 * fragments. Boots the app, seeds one execution + review, and asserts every
 * UI page renders through the shared layout.
 *
 * <p>Runs with authentication on and signs every request in with HTTP Basic, so the
 * layout's session controls render the way a real signed-in console does. The
 * dashboard snapshot cache is disabled because two methods share one context and the
 * second seeds new token rows a cached snapshot would hide.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false",
                "factory.dashboard.stats-cache-ttl=0s"})
@Import(StubLlmConfiguration.class)
@Testcontainers
class UiRenderSmokeTest {

    private static final String USER = TestAuthCredentials.USER;
    private static final String PASSWORD = TestAuthCredentials.PASSWORD;

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        TestAuthCredentials.register(registry);
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    @Autowired
    private PipelineExecutionRepository executions;

    @Autowired
    private HitlReviewRepository reviews;

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private ITemplateEngine templateEngine;

    private RestClient rest;

    /** No credentials: for the pages that must render before anyone has signed in. */
    private RestClient anonymous;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(USER, PASSWORD))
                .build();
        anonymous = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void dashboardRenders() {
        String body = assertRendered("/", "Dashboard");
        assertThat(body)
                .contains("chart.umd.js")
                .contains("/js/charts.js")
                .contains("class=\"charts-grid\"");
    }

    @Test
    void dashboardRendersTokenKpiAndBreakdownTable() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "test-automation", "engine",
                Map.of("promptTokens", 1786, "completionTokens", 353));

        String body = assertRendered("/", "LLM tokens");
        assertThat(body)
                .contains("Token spend by step")
                // Grouped thousands, and the per-step row reached the table body.
                .contains("2,139")
                .contains("test-automation");
    }

    @Test
    void loginPageRendersWithoutCredentialsAndOutsideTheConsoleShell() {
        ResponseEntity<String> response = anonymous.get().uri("/login").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("for=\"username\"")
                .contains("for=\"password\"")
                // Rendered by Spring Security's RequestDataValueProcessor; without it the
                // form would be rejected by the console chain's own CSRF filter.
                .contains("name=\"_csrf\"")
                .contains("/css/tokens.css")
                // No sidebar to navigate: the login page deliberately bypasses the layout.
                .doesNotContain("app-shell");
    }

    @Test
    void signedInLayoutShowsTheUserAndAPostLogoutControl() {
        String body = assertRendered("/", "Dashboard");
        assertThat(body)
                .contains("Signed in as")
                .contains(USER)
                .contains("action=\"/logout\"")
                .contains("Sign out");
    }

    @Test
    void chartJsWebjarServes() {
        ResponseEntity<String> response = rest.get()
                .uri("/webjars/chart.js/dist/chart.umd.js")
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Chart");
    }

    @Test
    void reviewsListRenders() {
        assertRendered("/reviews", "Human-in-the-loop gates and their decisions.");
    }

    @Test
    void executionsListRenders() {
        assertRendered("/executions", "Pipeline runs across all flows, most recent first.");
    }

    @Test
    void reviewsAllFilterRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        HitlReview review = reviews.save(new HitlReview(execution.getId(), "gate-2-signoff", 5,
                "{\"title\":\"Signed-off plan\",\"flowId\":\"test-factory\"}"));
        review.decide(HitlReviewStatus.AMENDED, "AMEND", USER, "tightened", null);
        reviews.save(review);

        // The ALL tab is not the PENDING path: it must render the decided review and
        // the decision columns (only shown when showDecision is true), catching a
        // regression that fell back to the pending-only view.
        String body = assertRendered("/reviews?status=ALL", "Human-in-the-loop gates and their decisions.");
        assertThat(body).contains("Signed-off plan").contains("Decided");
    }

    @Test
    void promptsListShowsReadOnlyView() {
        String body = assertRendered("/prompts", "Bundled prompt templates");
        assertThat(body).contains(">View<").doesNotContain("Overridden");
    }

    @Test
    void statusPageRenders() {
        assertRendered("/status", "Execution engine");
    }

    @Test
    void auditPageRenders() {
        assertRendered("/audit", "Append-only record");
    }

    @Test
    void flowsListRenders() {
        assertRendered("/flows", "Registered flow plugins");
    }

    @Test
    void flowDetailRenders() {
        String body = assertRendered("/flows/test-factory", "Retry policy");
        // The trigger-dialog sample <script> block must hold raw (un-escaped) JSON so the
        // sample-fill helper copies valid text into the textarea. The escaped form only
        // appears in the textarea itself, where the browser decodes it back.
        assertThat(body)
                .contains("type=\"application/json\"")
                .contains("\"issueKey\"");
    }

    @Test
    void workersListRenders() {
        assertRendered("/workers", "agent worker library");
    }

    @Test
    void promptsListRenders() {
        assertRendered("/prompts", "Bundled prompt templates");
    }

    @Test
    void promptViewerRendersActualPromptContent() {
        // Regression: a model attribute named `content` is shadowed by the layout
        // fragment's `content` parameter, which made this page render its own
        // template source instead of the prompt. Only a full render catches that,
        // so assert on the real bundled prompt text.
        String body = assertRendered("/prompts/test-spec-agent?file=user", "Bundled default");
        assertThat(body)
                .contains("Generate the manual test plan")
                .doesNotContain("th:text");
    }

    @Test
    void executionDetailRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        artifactStore.putMarkdown(execution.getId(), "test_plan.md", "# Plan\n", "test-spec");
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "triage", "engine",
                Map.of("promptTokens", 734, "completionTokens", 110));

        String body = assertRendered("/executions/" + execution.getId(), "Audit timeline");
        assertThat(body)
                .contains("LLM tokens: 844 total")
                .contains("844 tokens (734 in / 110 out)");
    }

    @Test
    void artifactsListAndDetailRender() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        var artifact = artifactStore.putMarkdown(execution.getId(), "coverage_report.md",
                "# Coverage\nAll branches hit.\n", "test-exec");

        String list = assertRendered("/artifacts", "Immutable artifact versions");
        assertThat(list).contains("coverage_report.md").contains("test-factory");

        String detail = assertRendered("/artifacts/" + artifact.getId(), "All branches hit.");
        assertThat(detail).contains("coverage_report.md").contains("test-exec");
    }

    @Test
    void reviewDetailRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        String reviewPackage = "{\"title\":\"Review test plan\",\"flowId\":\"test-factory\","
                + "\"instructions\":\"Please review the plan.\","
                + "\"artifacts\":[{\"name\":\"test_plan.md\",\"version\":1,\"content\":\"# Plan\"}]}";
        HitlReview review = reviews.save(new HitlReview(execution.getId(), "qa-gate-1", 2, reviewPackage));

        assertRendered("/reviews/" + review.getId(), "Instructions");
    }

    @Test
    void stepperAndPaginationFragmentsRender() {
        List<Map<String, Object>> steps = List.of(
                Map.of("stepId", "s1", "type", "AGENT", "label", "Triage",
                        "sublabel", "scope manifest", "state", "done", "attempts", 0),
                Map.of("stepId", "s2", "type", "HITL_GATE", "label", "QA gate",
                        "state", "current", "attempts", 2));
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 10);

        Context context = new Context();
        context.setVariable("steps", steps);
        context.setVariable("page", page);
        context.setVariable("slice", new SliceImpl<>(List.of("a", "b"), PageRequest.of(1, 2), true));
        context.setVariable("baseUrl", "/executions?status=RUNNING");

        String html = templateEngine.process("fragment-smoke", context);

        assertThat(html)
                .contains("class=\"stepper\"")
                .contains("Triage")
                .contains("retry ×2")
                .contains("class=\"pagination\"")
                .contains("&amp;page=0")
                .contains("&amp;page=2")
                // slicePagination: no total count, but prev/next links still render
                .contains("Page 2");
    }

    @Test
    void unknownExecutionRendersHtmlErrorPage() {
        ResponseEntity<String> response = rest.get()
                .uri("/executions/" + UUID.randomUUID())
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .contains("app-shell")
                .contains("Not found");
    }

    // Companion to unknownExecutionRendersHtmlErrorPage: the same miss on the REST
    // route must hit ApiExceptionHandler, not the HTML advice — this is the only
    // fully-wired check of the Api-vs-Ui advice precedence.
    @Test
    void unknownExecutionApiReturnsJsonError() {
        ResponseEntity<String> response = rest.get()
                .uri("/api/executions/" + UUID.randomUUID())
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("json");
        assertThat(response.getBody()).contains("\"error\"").contains("No execution");
    }

    private String assertRendered(String path, String marker) {
        ResponseEntity<String> response = rest.get().uri(path).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("app-shell")
                .contains(marker);
        return response.getBody();
    }
}
