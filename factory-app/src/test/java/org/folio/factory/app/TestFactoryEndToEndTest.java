package org.folio.factory.app;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full Test Factory pipeline through the real poller, REST API, WireMock'd Jira /
 * GitHub / TestRail and a scripted LLM: trigger → triage (Jira fetch) → test
 * plan → QA amend at gate 1 → script generation → QA approve at the script-review
 * gate → advisory execution → QA approve at gate 2 → finalizer syncs all three
 * connectors.
 *
 * <p>Runs with authentication ON and drives every REST call over HTTP Basic, so the
 * pipeline is proven through the real security chain rather than around it, and the
 * reviewer recorded in the ledger is the container-verified principal.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250",
        "factory.auth.enabled=true"
})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class TestFactoryEndToEndTest {

    /** The only account configured above; every REST call authenticates as it. */
    private static final String REVIEWER = TestAuthCredentials.USER;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer jira = new WireMockServer(0);
    static WireMockServer gitHub = new WireMockServer(0);
    static WireMockServer testRail = new WireMockServer(0);

    @DynamicPropertySource
    static void connectorProperties(DynamicPropertyRegistry registry) {
        TestAuthCredentials.register(registry);
        jira.start();
        gitHub.start();
        testRail.start();
        registry.add("factory.connectors.jira.base-url", jira::baseUrl);
        registry.add("factory.connectors.jira.email", () -> "bot@example.org");
        registry.add("factory.connectors.jira.api-token", () -> "token");
        registry.add("factory.connectors.github.base-url", gitHub::baseUrl);
        registry.add("factory.connectors.github.token", () -> "ghp_test");
        registry.add("factory.connectors.testrail.base-url", testRail::baseUrl);
        registry.add("factory.connectors.testrail.username", () -> "bot");
        registry.add("factory.connectors.testrail.api-key", () -> "key");
        registry.add("factory.connectors.testrail.project-id", () -> "12");
        registry.add("factory.test-factory.target-repo", () -> "folio-org/mod-agreements");
        registry.add("factory.test-factory.jira-transition", () -> "QA Complete");
        registry.add("factory.test-factory.testrail-section-id", () -> "55");
    }

    @AfterAll
    static void stopWireMock() {
        jira.stop();
        gitHub.stop();
        testRail.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    Environment environment;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    private final JsonMapper json = JsonMapper.builder().build();

    private RestClient rest;

    @BeforeEach
    void setUpClientAndStubs() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(REVIEWER, TestAuthCredentials.PASSWORD))
                .build();
        jira.resetAll();
        gitHub.resetAll();
        testRail.resetAll();

        jira.stubFor(get(urlEqualTo("/rest/api/2/issue/ERM-1001")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"key": "ERM-1001",
                         "fields": {"summary": "Add agreement name validation",
                                    "description": "As a librarian I want agreement names validated. AC1: valid name creates agreement. AC2: empty name rejected.",
                                    "status": {"name": "Ready for QA"},
                                    "issuetype": {"name": "Story"},
                                    "labels": []}}
                        """)));
        jira.stubFor(post(urlEqualTo("/rest/api/2/issue/ERM-1001/comment"))
                .willReturn(aResponse().withStatus(201)));
        jira.stubFor(get(urlEqualTo("/rest/api/2/issue/ERM-1001/transitions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"transitions\": [{\"id\": \"31\", \"name\": \"QA Complete\"}]}")));
        jira.stubFor(post(urlEqualTo("/rest/api/2/issue/ERM-1001/transitions"))
                .willReturn(aResponse().withStatus(204)));

        gitHub.stubFor(get(urlEqualTo("/repos/folio-org/mod-agreements/git/ref/heads/main"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"object\": {\"sha\": \"abc123\"}}")));
        gitHub.stubFor(post(urlEqualTo("/repos/folio-org/mod-agreements/git/refs"))
                .willReturn(aResponse().withStatus(201)));
        gitHub.stubFor(get(urlPathEqualTo("/repos/folio-org/mod-agreements/contents/features/agreements.feature"))
                .willReturn(aResponse().withStatus(404)));
        gitHub.stubFor(put(urlEqualTo("/repos/folio-org/mod-agreements/contents/features/agreements.feature"))
                .willReturn(aResponse().withStatus(201)));
        gitHub.stubFor(post(urlEqualTo("/repos/folio-org/mod-agreements/pulls"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"html_url\": \"https://github.com/folio-org/mod-agreements/pull/7\"}")));

        testRail.stubFor(post(urlPathEqualTo("/index.php")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": 991}")));
    }

    @Test
    void rawJiraWebhookBodyTriggersTheFlow() {
        // Real Jira webhook bodies have issue.key, not a top-level issueKey — the
        // adapter must normalise them so the declared trigger actually fires.
        ResponseEntity<JsonNode> response = rest.post()
                .uri("/api/webhooks/jira")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "webhookEvent", "jira:issue_updated",
                        "issue", Map.of(
                                "key", "ERM-1001",
                                "fields", Map.of("status", Map.of("name", "Ready for QA")))))
                .retrieve()
                .toEntity(JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().path("executionIds").size()).isEqualTo(1);
        UUID executionId = UUID.fromString(response.getBody().path("executionIds").get(0).asString());
        assertThat(stateManager.get(executionId).getFlowId()).isEqualTo("test-factory");
        assertThat(stateManager.get(executionId).getTriggerPayload()).contains("\"issueKey\"");

        // Park the execution before finishing: the engine keeps advancing it in the
        // background, and the next test's @BeforeEach resets the shared WireMock stubs.
        // An execution still mid-step would race that reset and make connector calls
        // fail nondeterministically. AWAITING_HITL is quiescent — no further HTTP.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL));
    }

    @Test
    void fullPipelineWithAmendmentAndConnectorSync() {
        // 1. Trigger manually with only the issue key — triage must fetch from Jira.
        ResponseEntity<JsonNode> triggerResponse = rest.post()
                .uri("/api/triggers/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flowId", "test-factory", "payload", Map.of("issueKey", "ERM-1001")))
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(triggerResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID executionId = UUID.fromString(triggerResponse.getBody().path("executionId").asString());

        // 2. Wait for HITL gate 1 and amend the test plan.
        JsonNode review1 = awaitPendingReview(executionId, "gate-1-test-plan");
        String planContent = artifactContent(review1, "test_plan.md");
        assertThat(planContent).contains("TC-01").contains("TC-02");
        String amendedPlan = planContent + "\n\n> Amended by QA: verified boundary coverage.\n";

        // The body still carries a reviewer name; the server must ignore it and record
        // the authenticated principal instead (asserted at step 5).
        ResponseEntity<JsonNode> decision1 = rest.post()
                .uri("/api/hitl/reviews/" + review1.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "AMEND", "reviewer", "someone-else", "comments", "tightened",
                        "amendedArtifacts", Map.of("test_plan.md", amendedPlan)))
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(decision1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decision1.getBody().path("status").asString()).isEqualTo("AMENDED");
        assertThat(decision1.getBody().path("reviewer").asString()).isEqualTo(REVIEWER);

        // 3. SEC-4: the generated scripts are reviewed by a human before anything runs
        // them, so this gate opens while test_results.md does not yet exist.
        JsonNode scriptReview = awaitPendingReview(executionId, "gate-script-review");
        assertThat(artifactContent(scriptReview, "test_scripts.md")).contains("Feature:");
        assertThat(artifactStore.getLatest(executionId, "test_results.md")).isEmpty();
        rest.post()
                .uri("/api/hitl/reviews/" + scriptReview.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "APPROVE"))
                .retrieve()
                .toEntity(JsonNode.class);

        // 4. Wait for HITL gate 3 (sign-off) and approve.
        JsonNode review2 = awaitPendingReview(executionId, "gate-2-signoff");
        assertThat(artifactContent(review2, "test_results.md")).contains("ADVISORY");
        rest.post()
                .uri("/api/hitl/reviews/" + review2.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "APPROVE"))
                .retrieve()
                .toEntity(JsonNode.class);

        // 5. Execution completes.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.COMPLETED));

        // 6. Artifacts: amended plan is v2 and downstream consumed it. The amendment is
        // attributed to the authenticated principal, not to the name in the request body.
        assertThat(artifactStore.getLatest(executionId, "test_plan.md").orElseThrow().getVersion()).isEqualTo(2);
        assertThat(artifactStore.getLatest(executionId, "test_plan.md").orElseThrow().getCreatedBy())
                .isEqualTo("hitl:" + REVIEWER);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId))
                .isNotEmpty()
                .allSatisfy(review -> assertThat(review.getReviewer()).isEqualTo(REVIEWER));
        assertThat(artifactStore.getLatest(executionId, "scope_manifest.md")).isPresent();
        assertThat(artifactStore.getLatest(executionId, "test_scripts.md").orElseThrow().getContent())
                .contains("Feature: Agreement name validation");
        String syncReport = artifactStore.getLatest(executionId, "sync_report.md").orElseThrow().getContent();
        // Assert every connector's outcome in the report, not just GitHub's: a silently
        // "failed" sync would otherwise only surface at the WireMock verifies below,
        // where the report's failure detail is no longer visible.
        //
        // These carry the connector request journals in their failure description. A rare
        // flake here reported `testrail ... failed — 404 Not Found: [no body]`, and the
        // report alone could not distinguish "the stub did not match" from "the request
        // never reached the server" — the journals separate the two on the next occurrence.
        assertThat(syncReport).as("sync report%s", connectorDiagnostics())
                .contains("https://github.com/folio-org/mod-agreements/pull/7")
                .contains("**github** branch + commit + PR: done")
                .contains("**testrail** cases + run: done")
                .contains("**jira** comment: done");

        // 7. Audit trail covers the whole lifecycle, including all three gates.
        var events = auditLog.forExecution(executionId);
        var eventTypes = events.stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).containsSubsequence(
                AuditEventType.EXECUTION_STARTED,
                AuditEventType.STEP_COMPLETED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.HITL_DECIDED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.HITL_DECIDED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.HITL_DECIDED,
                AuditEventType.CONNECTOR_ACTION,
                AuditEventType.EXECUTION_COMPLETED);
        // The caller who spent the run is named in the ledger; the engine's own rows stay 'system'.
        assertThat(events).filteredOn(e -> e.getEventType() == AuditEventType.TRIGGER_RECEIVED)
                .singleElement()
                .satisfies(e -> assertThat(e.getActor()).isEqualTo(REVIEWER));
        assertThat(events).filteredOn(e -> e.getEventType() == AuditEventType.HITL_DECIDED)
                .hasSize(3)
                .allSatisfy(e -> assertThat(e.getActor()).isEqualTo(REVIEWER));

        // 8. External systems really were called.
        jira.verify(getRequestedFor(urlEqualTo("/rest/api/2/issue/ERM-1001")));
        jira.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/ERM-1001/comment")));
        jira.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/ERM-1001/transitions")));
        gitHub.verify(postRequestedFor(urlEqualTo("/repos/folio-org/mod-agreements/git/refs")));
        gitHub.verify(putRequestedFor(
                urlEqualTo("/repos/folio-org/mod-agreements/contents/features/agreements.feature")));
        gitHub.verify(postRequestedFor(urlEqualTo("/repos/folio-org/mod-agreements/pulls")));
        // TestRail: 2 add_case calls + 1 add_run (advisory mode → no results call).
        testRail.verify(3, postRequestedFor(urlPathEqualTo("/index.php")));
    }

    /**
     * SEC-4's end-to-end guarantee: the generated Karate features are code derived from
     * an attacker-influenceable issue, so rejecting them at the script-review gate must
     * stop the run before the execution step ever runs them.
     */
    @Test
    void rejectingTheScriptReviewGateStopsTheRunBeforeAnythingExecutesTheScripts() {
        ResponseEntity<JsonNode> triggerResponse = rest.post()
                .uri("/api/triggers/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flowId", "test-factory", "payload", Map.of("issueKey", "ERM-1001")))
                .retrieve()
                .toEntity(JsonNode.class);
        UUID executionId = UUID.fromString(triggerResponse.getBody().path("executionId").asString());

        approve(awaitPendingReview(executionId, "gate-1-test-plan"));

        JsonNode scriptReview = awaitPendingReview(executionId, "gate-script-review");
        rest.post()
                .uri("/api/hitl/reviews/" + scriptReview.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "REJECT", "comments", "reads /etc/passwd"))
                .retrieve()
                .toEntity(JsonNode.class);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.REJECTED));
        assertThat(artifactStore.getLatest(executionId, "test_scripts.md")).isPresent();
        assertThat(artifactStore.getLatest(executionId, "test_results.md")).isEmpty();
        assertThat(artifactStore.getLatest(executionId, "sync_report.md")).isEmpty();
    }

    @Test
    void invalidAmendmentAtGate1IsRejectedWithoutNewArtifactVersion() {
        ResponseEntity<JsonNode> triggerResponse = rest.post()
                .uri("/api/triggers/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("flowId", "test-factory", "payload", Map.of("issueKey", "ERM-1001")))
                .retrieve()
                .toEntity(JsonNode.class);
        UUID executionId = UUID.fromString(triggerResponse.getBody().path("executionId").asString());

        JsonNode review = awaitPendingReview(executionId, "gate-1-test-plan");

        // Stripping the frontmatter must be rejected by the amendment validator
        // inside decide(), leaving the review PENDING and the artifact at v1.
        ResponseEntity<JsonNode> decision = rest.post()
                .uri("/api/hitl/reviews/" + review.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "AMEND",
                        "amendedArtifacts", Map.of("test_plan.md", "plain text, no frontmatter")))
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(JsonNode.class);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(decision.getBody().path("error").asString()).contains("not structurally valid");
        assertThat(artifactStore.getLatest(executionId, "test_plan.md").orElseThrow().getVersion()).isEqualTo(1);
        assertThat(reviews.findById(UUID.fromString(review.path("id").asString())).orElseThrow().getStatus())
                .isEqualTo(HitlReviewStatus.PENDING);
        // The execution stays parked at the gate — quiescent for the next test's
        // WireMock reset, per the parking rule documented above.
        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
    }

    /**
     * Every request each connector stub server actually served, with the configured
     * base URL beside the real one. Empty journals mean the traffic never arrived;
     * {@code matched=false} entries mean it arrived and no stub matched.
     */
    private String connectorDiagnostics() {
        StringBuilder diagnostics = new StringBuilder();
        appendServerDiagnostics(diagnostics, "jira", jira, "factory.connectors.jira.base-url");
        appendServerDiagnostics(diagnostics, "github", gitHub, "factory.connectors.github.base-url");
        appendServerDiagnostics(diagnostics, "testrail", testRail, "factory.connectors.testrail.base-url");
        return diagnostics.toString();
    }

    private void appendServerDiagnostics(StringBuilder out, String name, WireMockServer server, String property) {
        out.append(String.format("%n  %s: running=%s actual=%s configured=%s stubs=%d",
                name, server.isRunning(), server.isRunning() ? server.baseUrl() : "-",
                environment.getProperty(property), server.getStubMappings().size()));
        for (ServeEvent event : server.getAllServeEvents()) {
            out.append(String.format("%n    %s %s -> %d matched=%s",
                    event.getRequest().getMethod(), event.getRequest().getUrl(),
                    event.getResponse().getStatus(), event.getWasMatched()));
        }
    }

    private void approve(JsonNode reviewDetail) {
        rest.post()
                .uri("/api/hitl/reviews/" + reviewDetail.path("id").asString() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "APPROVE"))
                .retrieve()
                .toEntity(JsonNode.class);
    }

    private JsonNode awaitPendingReview(UUID executionId, String gateId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                        .anyMatch(r -> r.getGateId().equals(gateId)
                                && r.getStatus() == HitlReviewStatus.PENDING)).isTrue());
        var review = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(r -> r.getGateId().equals(gateId) && r.getStatus() == HitlReviewStatus.PENDING)
                .findFirst().orElseThrow();
        return rest.get().uri("/api/hitl/reviews/" + review.getId()).retrieve().body(JsonNode.class);
    }

    private String artifactContent(JsonNode reviewDetail, String artifactName) {
        for (JsonNode artifact : reviewDetail.path("reviewPackage").path("artifacts")) {
            if (artifactName.equals(artifact.path("name").asString())) {
                return artifact.path("content").asString();
            }
        }
        throw new AssertionError("Review package has no artifact " + artifactName + ": " + reviewDetail);
    }
}
