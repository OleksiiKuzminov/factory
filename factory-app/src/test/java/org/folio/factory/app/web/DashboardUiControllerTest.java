package org.folio.factory.app.web;

import org.folio.factory.app.web.dashboard.DashboardStats.StepTokenCount;
import org.folio.factory.app.web.dashboard.DashboardStats.TokenUsage;
import org.folio.factory.app.web.dashboard.DashboardStatsService;
import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class DashboardUiControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private FlowRegistry flowRegistry;

    @Mock
    private FlowRegistryEntryRepository flowRegistryEntries;

    @Mock
    private PipelineExecutionRepository executions;

    @Mock
    private HitlReviewRepository reviews;

    @Mock
    private AuditEventRepository audit;

    @Mock
    private DashboardStatsService dashboardStats;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private record FakeConnector(String connectorName, boolean isConfigured) implements ConnectorHealth {
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvcWithConnectors(List.of(new FakeConnector("jira", true), new FakeConnector("github", false)));
    }

    private MockMvc mvcWithConnectors(List<ConnectorHealth> connectors) {
        EngineProperties engine = new EngineProperties(true, 2000L, 5, 4, 1800L, 60L, 3600L, 30, true);
        DashboardUiController controller = new DashboardUiController(
                connectors, flowRegistry, engine, flowRegistryEntries, executions, reviews, audit, json,
                dashboardStats);
        return MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    private void stubKpiCounts() {
        when(executions.countGroupedByStatus()).thenReturn(List.of(
                new Object[]{ExecutionStatus.PENDING, 2L},
                new Object[]{ExecutionStatus.RUNNING, 1L},
                new Object[]{ExecutionStatus.AWAITING_HITL, 3L},
                new Object[]{ExecutionStatus.COMPLETED, 10L},
                new Object[]{ExecutionStatus.FAILED_ESCALATED, 4L},
                new Object[]{ExecutionStatus.REJECTED, 1L},
                new Object[]{ExecutionStatus.CANCELLED, 2L}));
        when(reviews.countByStatus(HitlReviewStatus.PENDING)).thenReturn(5L);
        when(dashboardStats.tokenUsage(anyInt())).thenReturn(new TokenUsage(1234, 567));
        when(dashboardStats.stepTokens(anyInt())).thenReturn(List.of(
                new StepTokenCount("test-factory", "test-automation", 1000, 400),
                new StepTokenCount("test-factory", "triage", 234, 167)));
    }

    @Test
    void dashboard_rendersKpiCountsFromCheapRepoQueries() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("days", 14))
                .andExpect(model().attribute("totalExecutions", 23L))
                .andExpect(model().attribute("completedCount", 10L))
                .andExpect(model().attribute("awaitingHitlCount", 3L))
                .andExpect(model().attribute("pendingReviews", 5L))
                .andExpect(model().attribute("failedCount", 4L))
                .andExpect(model().attribute("cancelledCount", 2L))
                .andExpect(model().attribute("rejectedCount", 1L))
                .andExpect(model().attribute("engineEnabled", true))
                .andExpect(model().attribute("connectorsConfigured", 1))
                .andExpect(model().attribute("connectorsTotal", 2))
                .andExpect(model().attribute("tokenTotal", "1,801"))
                .andExpect(model().attribute("tokenBreakdown", "1,234 in · 567 out"));
    }

    @Test
    void dashboard_tokenRowsCarryGroupedCountsHeaviestFirst() throws Exception {
        stubKpiCounts();

        MvcResult result = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("stepTokens");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("stepId", "test-automation")
                .containsEntry("prompt", "1,000")
                .containsEntry("completion", "400")
                .containsEntry("total", "1,400");
        assertThat(rows.get(1)).containsEntry("stepId", "triage").containsEntry("total", "401");
    }

    @Test
    void dashboard_scopesTokenQueriesToTheClampedWindow() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/?days=999")).andExpect(status().isOk());

        // The unknown range falls back to 14, and the token queries must use that
        // same clamped value rather than the raw request parameter.
        verify(dashboardStats).tokenUsage(14);
        verify(dashboardStats).stepTokens(14);
    }

    @Test
    void dashboard_clampsUnknownDaysToDefault() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/").param("days", "999"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("days", 14));
    }

    @Test
    void dashboard_acceptsAllowedDays() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("days", 30));
    }

    @Test
    void status_rendersEngineConnectorAndFlowRows() throws Exception {
        when(flowRegistry.all()).thenReturn(List.of(
                new FlowDescriptor("test-factory", "Test Factory", "1.0.0", null, null, null, null, null)));
        when(flowRegistryEntries.findById(any())).thenReturn(java.util.Optional.empty());

        var result = mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(view().name("status"))
                .andExpect(model().attributeExists("engine", "connectors", "flows"))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> engine = (Map<String, Object>) result.getModelAndView().getModel().get("engine");
        assertThat(engine.get("enabled")).isEqualTo(true);
        assertThat(engine.get("pollIntervalMs")).isEqualTo(2000L);
        assertThat(engine.get("batchSize")).isEqualTo(5);
        assertThat(engine.get("workerThreads")).isEqualTo(4);
        assertThat(engine.get("leaseTimeoutSeconds")).isEqualTo(1800L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connectors =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("connectors");
        assertThat(connectors).extracting(c -> c.get("name")).containsExactly("github", "jira");
        assertThat(connectors).extracting(c -> c.get("configured")).containsExactly(false, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("flows");
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).get("id")).isEqualTo("test-factory");
        assertThat(flows.get(0).get("name")).isEqualTo("Test Factory");
        assertThat(flows.get(0).get("version")).isEqualTo("1.0.0");
    }

    @Test
    void dashboard_dedupesConnectorsConfiguredWins() throws Exception {
        stubKpiCounts();
        MockMvc deduped = mvcWithConnectors(List.of(
                new FakeConnector("jira", false), new FakeConnector("jira", true),
                new FakeConnector("github", false), new FakeConnector("github", false),
                new FakeConnector("testrail", true), new FakeConnector("testrail", false)));

        deduped.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("connectorsConfigured", 2))
                .andExpect(model().attribute("connectorsTotal", 3));
    }

    @Test
    void status_dedupesConnectorsConfiguredWinsSortedByName() throws Exception {
        when(flowRegistry.all()).thenReturn(List.of());
        MockMvc deduped = mvcWithConnectors(List.of(
                new FakeConnector("jira", false), new FakeConnector("jira", true),
                new FakeConnector("github", false), new FakeConnector("github", false),
                new FakeConnector("testrail", true)));

        var result = deduped.perform(get("/status"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connectors =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("connectors");
        assertThat(connectors).extracting(c -> c.get("name")).containsExactly("github", "jira", "testrail");
        assertThat(connectors).extracting(c -> c.get("configured")).containsExactly(false, true, true);
    }

    @Test
    void audit_noEventType_usesUnfilteredPagedQuery() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        mvc.perform(get("/audit"))
                .andExpect(status().isOk())
                .andExpect(view().name("audit"))
                .andExpect(model().attributeExists("events", "page", "eventTypes"));

        verify(audit).findAllByOrderByIdDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void audit_eventTypeOptions_carryHumanizedLabels() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        var result = mvc.perform(get("/audit")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> options =
                (List<Map<String, String>>) result.getModelAndView().getModel().get("eventTypes");
        assertThat(options)
                .contains(Map.of("value", "HITL_REQUESTED", "label", "Review requested"))
                .contains(Map.of("value", "SUBFLOW_INVOKED", "label", "Sub-flow invoked"))
                .contains(Map.of("value", "STATE_TRANSITION", "label", "State transition"))
                .as("a failed connector sync must be filterable, not only visible in the feed")
                .contains(Map.of("value", "CONNECTOR_FAILED", "label", "Connector failed"));
    }

    @Test
    void audit_connectorFailedRowCarriesTheFailureTone() throws Exception {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), AuditEventType.CONNECTOR_FAILED, "finalize",
                "worker:test-factory-finalizer", "{\"connector\":\"testrail\"}");
        when(audit.findAllByOrderByIdDesc(any()))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        var result = mvc.perform(get("/audit")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("events");
        assertThat(events.get(0))
                .containsEntry("eventLabel", "Connector failed")
                .containsEntry("eventTone", "destructive");
    }

    @Test
    void audit_validEventType_dispatchesToTypedQueryWithPageable() throws Exception {
        when(audit.findByEventTypeOrderByIdDesc(eq(AuditEventType.STEP_COMPLETED), any()))
                .thenReturn(emptyPage(2, 10));

        mvc.perform(get("/audit").param("eventType", "STEP_COMPLETED").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedEventType", "STEP_COMPLETED"));

        verify(audit).findByEventTypeOrderByIdDesc(eq(AuditEventType.STEP_COMPLETED), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void audit_nonPositiveSize_fallsBackToDefaultPageSize() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        mvc.perform(get("/audit").param("size", "0"))
                .andExpect(status().isOk());

        verify(audit).findAllByOrderByIdDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void audit_invalidEventType_treatedAsAll() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        mvc.perform(get("/audit").param("eventType", "NOT_A_TYPE"))
                .andExpect(status().isOk());

        verify(audit).findAllByOrderByIdDesc(any());
    }

    @Test
    void audit_rowsCarryPrettyDetailAndExecutionLink() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        AuditEvent event = new AuditEvent(executionId, AuditEventType.STEP_COMPLETED, "triage", "engine",
                "{\"note\":\"done\"}");
        Page<AuditEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1);
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(page);

        var result = mvc.perform(get("/audit")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("events");
        assertThat(events).hasSize(1);
        Map<String, Object> row = events.get(0);
        assertThat(row.get("eventType")).isEqualTo("STEP_COMPLETED");
        assertThat(row.get("eventLabel")).isEqualTo("Step completed");
        assertThat(row.get("eventTone")).isNull();
        assertThat(row.get("executionId")).isEqualTo(executionId);
        assertThat(row.get("detail").toString()).contains("\n").contains("\"note\"");
    }

    private static Page<AuditEvent> emptyPage(int page, int size) {
        return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
    }
}
