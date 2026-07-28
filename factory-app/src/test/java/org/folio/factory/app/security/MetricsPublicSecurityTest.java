package org.folio.factory.app.security;

import org.folio.factory.app.StubLlmConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code factory.auth.metrics-public=true} opens the scrape endpoints to an
 * unauthenticated Prometheus, and nothing else.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.auth.enabled=true",
        "factory.auth.metrics-public=true"
})
@Import(StubLlmConfiguration.class)
class MetricsPublicSecurityTest extends AbstractSecurityIntegrationTest {

    @Test
    void prometheusEndpoint_publicWhenMetricsArePublic() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void metricsEndpoint_publicWhenMetricsArePublic() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpoints_stillAuthenticated() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    @Test
    void consolePage_stillAuthenticated() throws Exception {
        mvc.perform(get("/executions")).andExpect(status().is3xxRedirection());
    }
}
