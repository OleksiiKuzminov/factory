package org.folio.factory.app.web.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DashboardStats(Instant generatedAt, int days,
        List<StatusCount> executionsByStatus, List<FlowStatusCount> executionsByFlowAndStatus,
        List<DailyCount> executionsPerDay, List<StepFailureCount> stepFailures, HitlStats hitl,
        List<ConnectorOutcome> connectorOutcomes, TokenUsage tokens, List<StepTokenCount> stepTokens) {

    // One instance is cached and handed to concurrent dashboard requests, so the
    // mutable ArrayLists JdbcClient returns are copied to genuinely immutable ones.
    public DashboardStats {
        executionsByStatus = List.copyOf(executionsByStatus);
        executionsByFlowAndStatus = List.copyOf(executionsByFlowAndStatus);
        executionsPerDay = List.copyOf(executionsPerDay);
        stepFailures = List.copyOf(stepFailures);
        connectorOutcomes = List.copyOf(connectorOutcomes);
        stepTokens = List.copyOf(stepTokens);
    }

    public record StatusCount(String status, long count) {
    }

    public record FlowStatusCount(String flowId, String status, long count) {
    }

    public record DailyCount(LocalDate day, String status, long count) {
    }

    public record StepFailureCount(String flowId, String stepId, long failures, long retriesScheduled) {
    }

    public record HitlStats(long pending, Double oldestPendingAgeSeconds, long decidedInWindow,
                            Double avgDecisionSeconds) {
    }

    public record ConnectorOutcome(String connector, String eventType, long count) {
    }

    /** LLM tokens consumed by agent steps that completed inside the window. */
    public record TokenUsage(long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    public record StepTokenCount(String flowId, String stepId, long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
