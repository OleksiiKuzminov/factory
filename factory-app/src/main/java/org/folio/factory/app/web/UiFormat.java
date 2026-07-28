package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.registry.model.StepDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared formatting and request-parsing helpers for the server-rendered views.
 * All methods are pure so the controllers stay thin and consistent.
 */
final class UiFormat {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private UiFormat() {
    }

    /** Formats an instant as {@code yyyy-MM-dd HH:mm:ss} UTC, or {@code null} when the instant is null. */
    static String format(Instant instant) {
        return instant == null ? null : TIMESTAMP.format(instant);
    }

    static String abbreviate(String value) {
        return value.length() <= 13 ? value : value.substring(0, 10) + "...";
    }

    /** Groups a count with thousands separators — token totals reach seven figures. */
    static String count(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    static String stepLabel(StepDescriptor step) {
        return switch (step.type()) {
            case AGENT -> step.workerId();
            case HITL_GATE -> step.gate().title();
            case SUB_FLOW -> "Sub-flow: " + step.subFlow().flowId();
        };
    }

    /** Lenient enum parse: blank yields null, and any unknown value yields null rather than throwing. */
    static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String errorRedirect(String path, String message) {
        return "redirect:" + path + "?error=" + encode(message == null ? "Request failed" : message);
    }

    /** The one row shape the {@code auditTable} fragment renders, wherever it appears. */
    static Map<String, Object> auditRow(AuditEvent event, JsonMapper jsonMapper) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("occurredAt", format(event.getOccurredAt()));
        row.put("eventType", event.getEventType().name());
        row.put("eventLabel", eventLabel(event.getEventType()));
        row.put("eventTone", eventTone(event.getEventType()));
        row.put("stepId", event.getStepId());
        row.put("actor", event.getActor());
        row.put("executionId", event.getExecutionId());
        row.put("executionShort", event.getExecutionId() == null ? null
                : abbreviate(event.getExecutionId().toString()));
        row.put("detail", prettyDetail(event.getDetail(), jsonMapper));
        return row;
    }

    /** Human label for an audit event: hand-named where the enum reads poorly, sentence case otherwise. */
    static String eventLabel(AuditEventType type) {
        return switch (type) {
            case HITL_REQUESTED -> "Review requested";
            case HITL_DECIDED -> "Review decided";
            case SUBFLOW_INVOKED -> "Sub-flow invoked";
            case SUBFLOW_RETURNED -> "Sub-flow returned";
            default -> {
                String words = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    /** Badge tone for an audit event, or null for the neutral outline badge. */
    static String eventTone(AuditEventType type) {
        return switch (type) {
            case STEP_FAILED, ESCALATED, EXECUTION_BUDGET_EXCEEDED, CONNECTOR_FAILED -> "destructive";
            case RETRY_SCHEDULED -> "warning";
            case EXECUTION_COMPLETED -> "success";
            default -> null;
        };
    }

    private static String prettyDetail(String detail, JsonMapper jsonMapper) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        JsonNode node = jsonMapper.readTree(detail);
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }
}
