package org.folio.factory.core.service;

import org.folio.factory.core.agent.WorkerAuditSink;
import org.folio.factory.core.domain.AuditEventType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes worker side effects to the append-only ledger. Both narrowings the SPI
 * promises are enforced here rather than trusted to the caller: the actor is derived
 * from the worker id, so a plugin cannot attribute its own writes to {@code system},
 * and the event type is chosen by which method was called, so a plugin cannot forge
 * an engine event.
 */
@Service
public class LedgerWorkerAuditSink implements WorkerAuditSink {

    private final AuditLog auditLog;
    private final JdbcClient jdbc;

    public LedgerWorkerAuditSink(AuditLog auditLog, JdbcClient jdbc) {
        this.auditLog = auditLog;
        this.jdbc = jdbc;
    }

    @Override
    public void connectorAction(UUID executionId, String stepId, String workerId,
                                String connector, String actionKey, String label, String detail) {
        record(AuditEventType.CONNECTOR_ACTION, executionId, stepId, workerId, connector, actionKey, label, detail);
    }

    @Override
    public void connectorSkipped(UUID executionId, String stepId, String workerId,
                                 String connector, String actionKey, String label, String detail) {
        record(AuditEventType.CONNECTOR_SKIPPED, executionId, stepId, workerId, connector, actionKey, label, detail);
    }

    @Override
    public void connectorFailed(UUID executionId, String stepId, String workerId,
                                String connector, String actionKey, String label, String detail) {
        record(AuditEventType.CONNECTOR_FAILED, executionId, stepId, workerId, connector, actionKey, label, detail);
    }

    /**
     * Native SQL because the match is on JSON detail fields, which JPQL cannot
     * express (see {@code DashboardStatsService} for the same reasoning). Only
     * CONNECTOR_ACTION counts: a skipped or failed side effect did NOT happen, so
     * treating either as completed would silently drop it on the next attempt. The
     * COALESCE keeps a completed-but-detail-less action distinguishable from an
     * action that never completed, which is the whole point of the Optional.
     */
    @Override
    public Optional<String> completedDetail(UUID executionId, String connector, String actionKey) {
        return jdbc.sql("""
                        SELECT COALESCE(detail->>'detail', '') FROM audit_event
                        WHERE execution_id = :executionId
                          AND event_type = 'CONNECTOR_ACTION'
                          AND detail->>'connector' = :connector
                          AND detail->>'actionKey' = :actionKey
                        ORDER BY id
                        LIMIT 1
                        """)
                .param("executionId", executionId)
                .param("connector", connector)
                .param("actionKey", actionKey)
                .query(String.class)
                .optional();
    }

    private void record(AuditEventType type, UUID executionId, String stepId, String workerId,
                        String connector, String actionKey, String label, String detail) {
        require(workerId, "workerId");
        require(connector, "connector");
        // A blank action key would make completedDetail() never match, silently
        // turning a non-idempotent side effect into a repeated one on every retry.
        require(actionKey, "actionKey");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("connector", connector);
        payload.put("actionKey", actionKey);
        payload.put("action", label);
        payload.put("detail", detail);
        auditLog.record(executionId, type, stepId, "worker:" + workerId, payload);
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Connector audit record needs a non-blank " + field);
        }
    }
}
