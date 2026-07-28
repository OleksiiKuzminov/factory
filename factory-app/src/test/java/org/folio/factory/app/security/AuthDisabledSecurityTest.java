package org.folio.factory.app.security;

import org.folio.factory.app.StubLlmConfiguration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code factory.auth.enabled=false} is the escape hatch for deployments already
 * fronted by an authenticating proxy: it must genuinely bypass authentication and
 * CSRF, and attribute decisions to {@code anonymous} rather than to anything the
 * client sends.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.auth.enabled=false"
})
@Import(StubLlmConfiguration.class)
class AuthDisabledSecurityTest extends AbstractSecurityIntegrationTest {

    @Autowired
    private PipelineExecutionRepository executions;

    @Autowired
    private HitlReviewRepository reviews;

    @Autowired
    private StateManager stateManager;

    @Test
    void consolePage_servedWithoutALogin() throws Exception {
        mvc.perform(get("/executions")).andExpect(status().isOk());
    }

    @Test
    void apiEndpoint_servedWithoutCredentials() throws Exception {
        mvc.perform(get("/api/hitl/reviews")).andExpect(status().isOk());
    }

    @Test
    void actuatorEndpoint_servedWithoutCredentials() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void consoleFormPost_acceptedWithoutACsrfToken() throws Exception {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        stateManager.transition(execution.getId(), ExecutionStatus.AWAITING_HITL, null);
        HitlReview review = reviews.save(new HitlReview(execution.getId(), "qa-gate-1", 0, "{\"title\":\"t\"}"));

        mvc.perform(post("/reviews/{id}/decision", review.getId()).param("decision", "APPROVE"))
                .andExpect(status().is3xxRedirection());

        assertThat(reviews.findById(review.getId()).orElseThrow().getReviewer())
                .isEqualTo(CurrentUser.ANONYMOUS);
    }

    @Test
    void hitlDecisionOverRest_attributedToAnonymousNotToTheClientSuppliedReviewer() throws Exception {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        stateManager.transition(execution.getId(), ExecutionStatus.AWAITING_HITL, null);
        HitlReview review = reviews.save(new HitlReview(execution.getId(), "qa-gate-1", 0, "{\"title\":\"t\"}"));

        mvc.perform(post("/api/hitl/reviews/{id}/decision", review.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewer\":\"someone-else\"}"))
                .andExpect(status().isOk());

        assertThat(reviews.findById(review.getId()).orElseThrow().getReviewer())
                .isEqualTo(CurrentUser.ANONYMOUS);
    }

    @Test
    void consolePage_omitsTheSessionControlsWhenNobodyIsSignedIn() throws Exception {
        String body = mvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("app-shell").doesNotContain("Signed in as");
    }
}
