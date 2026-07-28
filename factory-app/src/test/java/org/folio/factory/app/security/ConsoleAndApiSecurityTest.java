package org.folio.factory.app.security;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.TestAuthCredentials;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-1 with authentication ON: the console, the REST API, the actuator endpoints and
 * the webhook receivers must each land on the right chain, and a HITL decision must
 * carry the container-verified reviewer rather than anything the client sent.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false"
})
@Import(StubLlmConfiguration.class)
class ConsoleAndApiSecurityTest extends AbstractSecurityIntegrationTest {

    private static final String USER = TestAuthCredentials.USER;
    private static final String PASSWORD = TestAuthCredentials.PASSWORD;
    private static final String WRONG_PASSWORD = PASSWORD + "-wrong";

    // A second account whose configured value already carries an encoder id: it must be
    // stored as given rather than encoded a second time.
    private static final String PRE_ENCODED_USER = "svc-noop";
    private static final String PRE_ENCODED_SECRET = UUID.randomUUID().toString();
    private static final String PRE_ENCODED_VALUE = "{noop}" + PRE_ENCODED_SECRET;

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        TestAuthCredentials.register(registry, PRE_ENCODED_USER + ":" + PRE_ENCODED_VALUE);
    }

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PipelineExecutionRepository executions;

    @Autowired
    private HitlReviewRepository reviews;

    @Autowired
    private StateManager stateManager;

    // ----- chain rules -----

    @Test
    void consolePage_unauthenticated_redirectedToTheLoginForm() throws Exception {
        mvc.perform(get("/executions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void apiEndpoint_unauthenticated_unauthorized() throws Exception {
        mvc.perform(get("/api/hitl/reviews"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiEndpoint_validBasicCredentials_ok() throws Exception {
        mvc.perform(get("/api/hitl/reviews").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * The console's own charts and pollers call {@code /api/**} with nothing but the
     * session cookie; the stateless write chain cannot authenticate that, so read-only
     * GETs get their own session-honouring chain.
     */
    @Test
    void apiRead_consoleSession_authenticatesWithoutCredentials() throws Exception {
        mvc.perform(get("/api/dashboard/stats").session(signedInConsoleSession()))
                .andExpect(status().isOk());
    }

    @Test
    void apiWrite_consoleSessionAlone_stillUnauthorizedSoItCannotBeDrivenCrossSite() throws Exception {
        mvc.perform(post("/api/triggers/manual")
                        .session(signedInConsoleSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"test-factory\",\"payload\":{\"issueKey\":\"ERM-1\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiEndpoint_wrongPassword_unauthorized() throws Exception {
        mvc.perform(get("/api/hitl/reviews").with(httpBasic(USER, WRONG_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiEndpoint_unknownUser_unauthorized() throws Exception {
        mvc.perform(get("/api/hitl/reviews").with(httpBasic("nobody", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void plainConfiguredPassword_isBcryptEncodedAtStartup() {
        assertThat(userDetailsService.loadUserByUsername(USER).getPassword())
                .startsWith("{bcrypt}$2a$");
    }

    @Test
    void preEncodedPassword_storedAsGivenSoItStillAuthenticates() throws Exception {
        assertThat(userDetailsService.loadUserByUsername(PRE_ENCODED_USER).getPassword())
                .isEqualTo(PRE_ENCODED_VALUE);

        mvc.perform(get("/api/hitl/reviews").with(httpBasic(PRE_ENCODED_USER, PRE_ENCODED_SECRET)))
                .andExpect(status().isOk());
    }

    /**
     * The endpoint is permitAll for probes, so {@code show-details: when-authorized} is
     * the only thing keeping the db/connector/diskSpace breakdown from an anonymous
     * caller. Status alone proves nothing — it is identical either way.
     */
    @Test
    void actuatorHealth_anonymous_publicForProbesButWithoutAnyComponentDetail() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void actuatorHealth_authenticated_showsTheComponentDetail() throws Exception {
        mvc.perform(get("/actuator/health").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").exists());
    }

    @Test
    void actuatorPrometheus_authenticatedWhileMetricsAreNotPublic() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/actuator/prometheus").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void webhookEndpoint_reachableWithoutALogin() throws Exception {
        mvc.perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookEvent\":\"jira:issue_updated\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    // ----- CSRF on the console -----

    @Test
    void consoleFormPost_withoutCsrfToken_forbidden() throws Exception {
        mvc.perform(post("/reviews/{id}/decision", UUID.randomUUID())
                        .with(httpBasic(USER, PASSWORD))
                        .param("decision", "APPROVE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void consoleFormPost_withCsrfToken_reachesTheController() throws Exception {
        mvc.perform(post("/reviews/{id}/decision", UUID.randomUUID())
                        .with(httpBasic(USER, PASSWORD))
                        .with(csrf())
                        .param("decision", "APPROVE"))
                .andExpect(status().is3xxRedirection());
    }

    // ----- reviewer identity -----

    @Test
    void hitlDecision_recordsTheAuthenticatedPrincipalAndIgnoresTheClientSuppliedReviewer()
            throws Exception {
        HitlReview review = pendingReviewAtAGate();

        mvc.perform(post("/api/hitl/reviews/{id}/decision", review.getId())
                        .with(httpBasic(USER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewer\":\"someone-else\"}"))
                .andExpect(status().isOk());

        assertThat(reviews.findById(review.getId()).orElseThrow().getReviewer()).isEqualTo(USER);
    }

    // ----- rendering -----

    @Test
    void loginPage_rendersLabelledFieldsAndACsrfTokenWithoutTheConsoleNavigation() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("for=\"username\"")))
                .andExpect(content().string(containsString("for=\"password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("app-shell"))));
    }

    @Test
    void loginPage_errorParameter_showsAGenericFailureMessage() throws Exception {
        mvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("Invalid username or password")));
    }

    @Test
    void loginPage_logoutParameter_showsTheSignedOutNotice() throws Exception {
        mvc.perform(get("/login").param("logout", ""))
                .andExpect(content().string(containsString("You have been signed out")));
    }

    @Test
    void consolePage_signedIn_showsTheUserAndAPostLogoutControlInTheSidebar() throws Exception {
        mvc.perform(get("/executions").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Signed in as")))
                .andExpect(content().string(containsString(USER)))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("Sign out")));
    }

    @Test
    void reviewPage_showsTheSignedInReviewerInsteadOfAFreeTextField() throws Exception {
        HitlReview review = pendingReviewAtAGate();

        mvc.perform(get("/reviews/{id}", review.getId()).with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Recorded as")))
                .andExpect(content().string(containsString(">" + USER + "</strong>")))
                .andExpect(content().string(not(containsString("name=\"reviewer\""))));
    }

    /** Signs in through the real form-login chain and returns the resulting session. */
    private MockHttpSession signedInConsoleSession() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", USER)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private HitlReview pendingReviewAtAGate() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        stateManager.transition(execution.getId(), ExecutionStatus.AWAITING_HITL, null);
        return reviews.save(new HitlReview(execution.getId(), "qa-gate-1", 0,
                "{\"title\":\"Review test plan\",\"flowId\":\"test-factory\","
                        + "\"instructions\":\"Please review the plan.\",\"artifacts\":[]}"));
    }
}
