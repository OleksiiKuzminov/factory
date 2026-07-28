package org.folio.factory.app.web;

import org.folio.factory.app.security.CurrentUser;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.limits.DailyBudgetExceededException;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.trigger.PipelineRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TriggerControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String FLOW_ID = "test-factory";

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private PipelineRouter router;

    @Mock
    private AuditLog auditLog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TriggerController(router, auditLog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void manual_malformedJsonBody_badRequestJsonError() throws Exception {
        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));

        verifyNoInteractions(router, auditLog);
    }

    @Test
    void manual_unsupportedMediaType_jsonError() throws Exception {
        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("flowId=test-factory"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value(containsString("text/plain")));

        verifyNoInteractions(router, auditLog);
    }

    @Test
    void manual_dedupKeyLongerThan255Chars_rejectedWithoutRouting() throws Exception {
        String oversize = "k".repeat(256);

        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"" + FLOW_ID + "\",\"payload\":{},\"dedupKey\":\"" + oversize + "\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("dedupKey")));

        verifyNoInteractions(router, auditLog);
    }

    @Test
    void manual_blankDedupKey_treatedAsAbsent() throws Exception {
        JsonNode payload = json.readTree("{\"issueKey\":\"ERM-1\"}");
        when(router.routeManual(eq(FLOW_ID), eq(payload), isNull())).thenReturn(EXECUTION_ID);

        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"" + FLOW_ID + "\",\"payload\":{\"issueKey\":\"ERM-1\"},\"dedupKey\":\"   \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()));
    }

    @Test
    void manual_validDedupKey_passedThroughTrimmed() throws Exception {
        JsonNode payload = json.readTree("{\"issueKey\":\"ERM-1\"}");
        when(router.routeManual(FLOW_ID, payload, "release-42")).thenReturn(EXECUTION_ID);

        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"" + FLOW_ID + "\",\"payload\":{\"issueKey\":\"ERM-1\"},\"dedupKey\":\" release-42 \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()));
    }

    @Test
    void manual_dailyBudgetExhausted_mappedToHttp429() throws Exception {
        when(router.routeManual(eq(FLOW_ID), any(), isNull()))
                .thenThrow(new DailyBudgetExceededException("Daily execution budget of 200 reached"));

        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"" + FLOW_ID + "\",\"payload\":{}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(containsString("budget")));
    }

    @Test
    void manual_authenticatedCaller_auditedAsTheTriggerActor() throws Exception {
        when(router.routeManual(eq(FLOW_ID), any(), isNull())).thenReturn(EXECUTION_ID);

        mvc.perform(post("/api/triggers/manual")
                        .principal(() -> "rel-mgr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"" + FLOW_ID + "\",\"payload\":{}}"))
                .andExpect(status().isAccepted());

        verify(auditLog).record(EXECUTION_ID, AuditEventType.TRIGGER_RECEIVED, null, "rel-mgr",
                Map.of("flowId", FLOW_ID, "trigger", "manual"));
    }

    @Test
    void manual_withoutPrincipal_auditedAsAnonymous() throws Exception {
        when(router.routeManual(eq(FLOW_ID), any(), isNull())).thenReturn(EXECUTION_ID);

        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flowId\":\"" + FLOW_ID + "\",\"payload\":{}}"))
                .andExpect(status().isAccepted());

        verify(auditLog).record(EXECUTION_ID, AuditEventType.TRIGGER_RECEIVED, null, CurrentUser.ANONYMOUS,
                Map.of("flowId", FLOW_ID, "trigger", "manual"));
    }

    @Test
    void manual_missingFlowId_rejectedWithoutRouting() throws Exception {
        mvc.perform(post("/api/triggers/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{}}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("flowId")));

        verifyNoInteractions(router, auditLog);
    }
}
