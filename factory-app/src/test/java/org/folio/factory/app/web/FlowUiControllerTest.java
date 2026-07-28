package org.folio.factory.app.web;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.app.security.CurrentUser;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.registry.model.SubFlowSpec;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.trigger.PipelineRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class FlowUiControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private FlowRegistry flowRegistry;

    @Mock
    private FlowRegistryEntryRepository mirror;

    @Mock
    private PipelineRouter router;

    @Mock
    private AgentWorkerRegistry workers;

    @Mock
    private PromptCatalog promptCatalog;

    @Mock
    private AuditLog auditLog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new FlowUiController(flowRegistry, mirror, router, workers, promptCatalog, json,
                        auditLog))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    // ----- flows list -----

    @Test
    @SuppressWarnings("unchecked")
    void flows_list_buildsSummaryRows() throws Exception {
        when(flowRegistry.all()).thenReturn(List.of(descriptor()));

        var result = mvc.perform(get("/flows"))
                .andExpect(status().isOk())
                .andExpect(view().name("flows"))
                .andReturn();

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("flows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("id", "test-factory")
                .containsEntry("name", "Test Factory")
                .containsEntry("version", "1.0.0")
                .containsEntry("stepCount", 3)
                .containsEntry("gateCount", 1);
        assertThat((List<String>) rows.get(0).get("eventTypes"))
                .containsExactly("manual", "jira.issue.transitioned");
    }

    // ----- flow detail -----

    @Test
    @SuppressWarnings("unchecked")
    void flow_detail_buildsStepsTriggersAndSamples() throws Exception {
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));
        when(mirror.findById(any())).thenReturn(Optional.empty());

        var result = mvc.perform(get("/flows/{id}", "test-factory"))
                .andExpect(status().isOk())
                .andExpect(view().name("flow"))
                .andExpect(model().attribute("rawYaml", (Object) null))
                .andReturn();

        Map<String, Object> model = (Map<String, Object>) (Map<?, ?>) result.getModelAndView().getModel();

        List<Map<String, Object>> steps = (List<Map<String, Object>>) model.get("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0))
                .containsEntry("type", "AGENT")
                .containsEntry("label", "triage-agent")
                .containsEntry("sublabel", "triage · AGENT")
                .containsEntry("state", "pending")
                .containsEntry("workerId", "triage-agent");
        assertThat(steps.get(1))
                .containsEntry("type", "HITL_GATE")
                .containsEntry("label", "QA gate");
        assertThat((Map<String, Object>) steps.get(1).get("gate")).containsEntry("gateId", "g1");
        assertThat(steps.get(2))
                .containsEntry("type", "SUB_FLOW")
                .containsEntry("label", "Sub-flow: child-flow");

        List<Map<String, Object>> triggers = (List<Map<String, Object>>) model.get("triggers");
        assertThat(triggers).hasSize(2);
        assertThat(triggers.get(1))
                .containsEntry("eventType", "jira.issue.transitioned");
        assertThat((Map<String, String>) triggers.get(1).get("filters"))
                .containsEntry("/issue/fields/status/name", "Ready for QA");

        Map<String, Object> retry = (Map<String, Object>) model.get("retryPolicy");
        assertThat(retry).containsEntry("maxAttempts", 3);

        // Real classpath samples for test-factory are discovered and payload-extracted.
        List<Map<String, Object>> samples = (List<Map<String, Object>>) model.get("samples");
        assertThat(samples).hasSize(2);
        assertThat(samples).allSatisfy(sample ->
                assertThat(String.valueOf(sample.get("prettyJson"))).contains("issueKey"));
        assertThat(String.valueOf(model.get("defaultPayload"))).contains("issueKey");
    }

    // ----- trigger POST -----

    @Test
    void trigger_validPayload_routesAndRedirectsToExecution() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
        when(router.routeManual(eq("test-factory"), any(JsonNode.class), isNull())).thenReturn(executionId);

        mvc.perform(post("/flows/{id}/trigger", "test-factory")
                        .param("payload", "{\"issueKey\":\"ERM-1\"}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions/" + executionId))
                .andExpect(flash().attribute("message", "Execution started"));

        verify(router).routeManual(eq("test-factory"), any(JsonNode.class), isNull());
    }

    @Test
    void trigger_authenticatedCaller_auditedAsTheConsoleTriggerActor() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-0000000000ef");
        when(router.routeManual(eq("test-factory"), any(JsonNode.class), isNull())).thenReturn(executionId);

        mvc.perform(post("/flows/{id}/trigger", "test-factory")
                        .principal(() -> "rel-mgr")
                        .param("payload", "{}"))
                .andExpect(status().is3xxRedirection());

        verify(auditLog).record(executionId, AuditEventType.TRIGGER_RECEIVED, null, "rel-mgr",
                Map.of("flowId", "test-factory", "trigger", "console"));
    }

    @Test
    void trigger_withoutPrincipal_auditedAsAnonymous() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
        when(router.routeManual(eq("test-factory"), any(JsonNode.class), isNull())).thenReturn(executionId);

        mvc.perform(post("/flows/{id}/trigger", "test-factory").param("payload", "{}"))
                .andExpect(status().is3xxRedirection());

        verify(auditLog).record(executionId, AuditEventType.TRIGGER_RECEIVED, null, CurrentUser.ANONYMOUS,
                Map.of("flowId", "test-factory", "trigger", "console"));
    }

    @Test
    void trigger_passesDedupKeyBlankAsNull() throws Exception {
        when(router.routeManual(eq("test-factory"), any(JsonNode.class), eq("k1")))
                .thenReturn(UUID.randomUUID());

        mvc.perform(post("/flows/{id}/trigger", "test-factory")
                        .param("payload", "{}").param("dedupKey", "  k1  "))
                .andExpect(status().is3xxRedirection());

        verify(router).routeManual(eq("test-factory"), any(JsonNode.class), eq("k1"));
    }

    @Test
    void trigger_emptyDedupKey_passesNull() throws Exception {
        when(router.routeManual(eq("test-factory"), any(JsonNode.class), isNull()))
                .thenReturn(UUID.randomUUID());

        mvc.perform(post("/flows/{id}/trigger", "test-factory")
                        .param("payload", "{}").param("dedupKey", ""))
                .andExpect(status().is3xxRedirection());

        verify(router).routeManual(eq("test-factory"), any(JsonNode.class), isNull());
    }

    @Test
    void escapeScriptClosers_neutralisesScriptClosersForJsonScriptBlock() {
        // A sample carrying "</script>" must render as the JSON-legal "<\/script>"
        // so it cannot break out of the <script type="application/json"> block.
        String escaped = FlowUiController.escapeScriptClosers("{\"html\":\"</script></p>\"}");
        assertThat(escaped).isEqualTo("{\"html\":\"<\\/script><\\/p>\"}").doesNotContain("</");
    }

    @Test
    void trigger_invalidJson_redirectsWithErrorAndDoesNotRoute() throws Exception {
        mvc.perform(post("/flows/{id}/trigger", "test-factory")
                        .param("payload", "{ not json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/flows/test-factory?error=")
                        .contains("Invalid+JSON"));

        verifyNoInteractions(router);
    }

    @Test
    void trigger_routerRejects_redirectsWithEncodedError() throws Exception {
        when(router.routeManual(eq("test-factory"), any(JsonNode.class), isNull()))
                .thenThrow(new FlowValidationException("requires input field 'issueKey'"));

        mvc.perform(post("/flows/{id}/trigger", "test-factory")
                        .param("payload", "{}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/flows/test-factory?error=")
                        .contains("issueKey"));
    }

    // ----- workers -----

    @Test
    @SuppressWarnings("unchecked")
    void workers_list_reportsLlmFlagSimpleNameAndUsedBy() throws Exception {
        AgentWorker llm = new LlmTestWorker();
        AgentWorker plain = new PlainTestWorker();
        when(workers.all()).thenReturn(Map.of("triage-agent", llm, "finalizer", plain));
        when(flowRegistry.all()).thenReturn(List.of(descriptor()));
        when(promptCatalog.promptsFor("triage-agent")).thenReturn(List.of("system", "user"));
        when(promptCatalog.promptsFor("finalizer")).thenReturn(List.of());

        var result = mvc.perform(get("/workers"))
                .andExpect(status().isOk())
                .andExpect(view().name("workers"))
                .andReturn();

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("workers");
        assertThat(rows).hasSize(2);
        // sorted by id: finalizer, triage-agent
        assertThat(rows.get(0))
                .containsEntry("id", "finalizer")
                .containsEntry("className", "PlainTestWorker")
                .containsEntry("llm", false);
        assertThat((List<?>) rows.get(0).get("usedBy")).isEmpty();

        assertThat(rows.get(1))
                .containsEntry("id", "triage-agent")
                .containsEntry("className", "LlmTestWorker")
                .containsEntry("llm", true);
        assertThat((List<String>) rows.get(1).get("prompts")).containsExactly("system", "user");
        List<Map<String, Object>> usedBy = (List<Map<String, Object>>) rows.get(1).get("usedBy");
        assertThat(usedBy).hasSize(1);
        assertThat(usedBy.get(0)).containsEntry("flowId", "test-factory").containsEntry("stepId", "triage");
    }

    // ----- helpers -----

    private static FlowDescriptor descriptor() {
        StepDescriptor agent = new StepDescriptor("triage", StepType.AGENT, "triage-agent",
                null, null, List.of("$trigger"), List.of("scope_manifest.md"), Map.of("framework", "karate"));
        StepDescriptor gate = new StepDescriptor("qa-gate", StepType.HITL_GATE, null,
                new HitlGateSpec("g1", "QA gate", "review it", List.of("test_plan.md")), null, null, null, null);
        StepDescriptor sub = new StepDescriptor("sub", StepType.SUB_FLOW, null, null,
                new SubFlowSpec("child-flow", Map.of("test_plan.md", "plan.md"), Map.of("out.md", "up.md")),
                null, null, null);
        List<TriggerContract> triggers = List.of(
                new TriggerContract("manual", Map.of()),
                new TriggerContract("jira.issue.transitioned", Map.of("/issue/fields/status/name", "Ready for QA")));
        return new FlowDescriptor("test-factory", "Test Factory", "1.0.0", triggers, null, null,
                List.of(agent, gate, sub), RetryPolicy.DEFAULT);
    }

    static final class LlmTestWorker extends AbstractLlmAgentWorker {
        LlmTestWorker() {
            super(null);
        }

        @Override
        public String id() {
            return "triage-agent";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return null;
        }
    }

    static final class PlainTestWorker implements AgentWorker {
        @Override
        public String id() {
            return "finalizer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return null;
        }
    }
}
