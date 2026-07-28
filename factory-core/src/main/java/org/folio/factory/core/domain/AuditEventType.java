package org.folio.factory.core.domain;

public enum AuditEventType {
    /**
     * A human or client asked for a run, recorded with that caller as the actor.
     * Distinct from {@link #EXECUTION_STARTED}, which the engine records as 'system'
     * once the execution row exists.
     */
    TRIGGER_RECEIVED,
    EXECUTION_STARTED,
    EXECUTION_COMPLETED,
    STATE_TRANSITION,
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_FAILED,
    RETRY_SCHEDULED,
    ESCALATED,
    ARTIFACT_WRITTEN,
    HITL_REQUESTED,
    HITL_DECIDED,
    SUBFLOW_INVOKED,
    SUBFLOW_RETURNED,
    CONNECTOR_ACTION,
    CONNECTOR_SKIPPED,
    CONNECTOR_FAILED,
    EXECUTION_DEDUPED,
    EXECUTION_BUDGET_EXCEEDED,
    RETENTION_PURGED
}
