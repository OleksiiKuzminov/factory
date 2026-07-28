package org.folio.factory.app.web;

import org.folio.factory.app.web.dashboard.DashboardStats.StepTokenCount;
import org.folio.factory.app.web.dashboard.DashboardStats.TokenUsage;
import org.folio.factory.app.web.dashboard.DashboardStatsService;
import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server-rendered operational views: the Dashboard home ({@code /}) with KPI
 * cards and interactive charts, the platform Status page (engine, connectors,
 * flow registry) and the Audit page (paged, filterable event feed). All draw on
 * the same beans as their REST counterparts.
 */
@Controller
public class DashboardUiController {

    private static final int DEFAULT_DAYS = 14;
    private static final int DEFAULT_AUDIT_SIZE = 50;

    private final List<ConnectorHealth> connectors;
    private final FlowRegistry flowRegistry;
    private final EngineProperties engineProperties;
    private final FlowRegistryEntryRepository flowRegistryEntries;
    private final PipelineExecutionRepository executions;
    private final HitlReviewRepository reviews;
    private final AuditEventRepository audit;
    private final JsonMapper jsonMapper;
    private final DashboardStatsService dashboardStats;

    public DashboardUiController(List<ConnectorHealth> connectors, FlowRegistry flowRegistry,
                                 EngineProperties engineProperties,
                                 FlowRegistryEntryRepository flowRegistryEntries,
                                 PipelineExecutionRepository executions, HitlReviewRepository reviews,
                                 AuditEventRepository audit, JsonMapper jsonMapper,
                                 DashboardStatsService dashboardStats) {
        this.connectors = connectors;
        this.flowRegistry = flowRegistry;
        this.engineProperties = engineProperties;
        this.flowRegistryEntries = flowRegistryEntries;
        this.executions = executions;
        this.reviews = reviews;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
        this.dashboardStats = dashboardStats;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "days", defaultValue = "14") int days, Model model) {
        // Clamped against the service's own list — a window it would reject reaches
        // compute() as an IllegalArgumentException and 500s the page, not 422s it.
        int window = DashboardStatsService.ALLOWED_WINDOWS.contains(days) ? days : DEFAULT_DAYS;
        model.addAttribute("days", window);

        Map<ExecutionStatus, Long> byStatus = new EnumMap<>(ExecutionStatus.class);
        for (Object[] row : executions.countGroupedByStatus()) {
            byStatus.put((ExecutionStatus) row[0], (Long) row[1]);
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        model.addAttribute("totalExecutions", total);
        model.addAttribute("completedCount", byStatus.getOrDefault(ExecutionStatus.COMPLETED, 0L));
        model.addAttribute("awaitingHitlCount", byStatus.getOrDefault(ExecutionStatus.AWAITING_HITL, 0L));
        model.addAttribute("failedCount", byStatus.getOrDefault(ExecutionStatus.FAILED_ESCALATED, 0L));
        model.addAttribute("cancelledCount", byStatus.getOrDefault(ExecutionStatus.CANCELLED, 0L));
        model.addAttribute("rejectedCount", byStatus.getOrDefault(ExecutionStatus.REJECTED, 0L));

        model.addAttribute("pendingReviews", reviews.countByStatus(HitlReviewStatus.PENDING));

        model.addAttribute("engineEnabled", engineProperties.enabled());
        Map<String, Boolean> connectorConfig = connectorConfiguration();
        int configured = (int) connectorConfig.values().stream().filter(Boolean::booleanValue).count();
        model.addAttribute("connectorsConfigured", configured);
        model.addAttribute("connectorsTotal", connectorConfig.size());

        // Unlike the all-time KPIs above, token spend is scoped to the selected window.
        TokenUsage tokens = dashboardStats.tokenUsage(window);
        model.addAttribute("tokenTotal", UiFormat.count(tokens.totalTokens()));
        model.addAttribute("tokenBreakdown",
                UiFormat.count(tokens.promptTokens()) + " in · " + UiFormat.count(tokens.completionTokens()) + " out");
        model.addAttribute("stepTokens", dashboardStats.stepTokens(window).stream()
                .map(DashboardUiController::stepTokenRow).toList());
        return "dashboard";
    }

    private static Map<String, Object> stepTokenRow(StepTokenCount step) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("flowId", step.flowId());
        row.put("stepId", step.stepId());
        row.put("prompt", UiFormat.count(step.promptTokens()));
        row.put("completion", UiFormat.count(step.completionTokens()));
        row.put("total", UiFormat.count(step.totalTokens()));
        return row;
    }

    @GetMapping("/status")
    public String status(Model model) {
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("enabled", engineProperties.enabled());
        engine.put("pollIntervalMs", engineProperties.pollIntervalMs());
        engine.put("batchSize", engineProperties.batchSize());
        engine.put("workerThreads", engineProperties.workerThreads());
        engine.put("leaseTimeoutSeconds", engineProperties.leaseTimeoutSeconds());
        model.addAttribute("engine", engine);

        List<Map<String, Object>> connectorRows = new ArrayList<>();
        connectorConfiguration().forEach((name, configured) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("configured", configured);
            connectorRows.add(row);
        });
        model.addAttribute("connectors", connectorRows);

        model.addAttribute("flows", flowRegistry.all().stream().map(this::flowRow).toList());
        return "status";
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(name = "eventType", required = false) String eventType,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        @RequestParam(name = "size", defaultValue = "50") int size,
                        Model model) {
        int clampedSize = PageValidation.clampSize(size, DEFAULT_AUDIT_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize);
        AuditEventType type = UiFormat.enumOrNull(AuditEventType.class, eventType);

        // Slice, not Page: the audit table is append-only and unbounded, so a total
        // count(*) per page view would degrade without limit.
        Slice<AuditEvent> result = type == null
                ? audit.findAllByOrderByIdDesc(pageable)
                : audit.findByEventTypeOrderByIdDesc(type, pageable);

        model.addAttribute("events", result.getContent().stream()
                .map(event -> UiFormat.auditRow(event, jsonMapper)).toList());
        model.addAttribute("page", result);
        model.addAttribute("eventTypes", Arrays.stream(AuditEventType.values())
                .map(t -> Map.of("value", t.name(), "label", UiFormat.eventLabel(t))).toList());
        model.addAttribute("selectedEventType", type == null ? "" : type.name());
        model.addAttribute("baseUrl", "/audit?eventType="
                + (type == null ? "" : type.name()) + "&size=" + clampedSize);
        return "audit";
    }

    private Map<String, Boolean> connectorConfiguration() {
        Map<String, Boolean> byName = new TreeMap<>();
        for (ConnectorHealth connector : connectors) {
            byName.merge(connector.connectorName(), connector.isConfigured(), (a, b) -> a || b);
        }
        return byName;
    }

    private Map<String, Object> flowRow(FlowDescriptor flow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", flow.id());
        row.put("name", flow.name());
        row.put("version", flow.version());
        row.put("registeredAt", flowRegistryEntries
                .findById(new FlowRegistryEntry.Key(flow.id(), flow.version()))
                .map(entry -> UiFormat.format(entry.getRegisteredAt()))
                .orElse("—"));
        return row;
    }

}
