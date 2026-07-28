package org.folio.factory.app.web;

import org.folio.factory.app.security.CurrentUser;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.trigger.PipelineRouter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/triggers")
public class TriggerController {

    private final PipelineRouter router;
    private final AuditLog auditLog;

    public TriggerController(PipelineRouter router, AuditLog auditLog) {
        this.router = router;
        this.auditLog = auditLog;
    }

    public record ManualTriggerRequest(String flowId, JsonNode payload, String dedupKey) {
    }

    // Matches the dedup_key varchar(255) column; an unvalidated longer key would only
    // surface as a DB constraint error deep in the router's dedup-race handling.
    private static final int MAX_DEDUP_KEY_LENGTH = 255;

    @PostMapping("/manual")
    public ResponseEntity<Map<String, String>> manual(@RequestBody ManualTriggerRequest request,
                                                      Principal principal) {
        if (request.flowId() == null || request.flowId().isBlank()) {
            throw new IllegalArgumentException("flowId is required");
        }
        UUID executionId = router.routeManual(request.flowId(), request.payload(),
                normalisedDedupKey(request.dedupKey()));
        // Spending an execution is a consequential action, so the ledger records who
        // asked for it: the router attributes the run itself to 'system'.
        auditLog.record(executionId, AuditEventType.TRIGGER_RECEIVED, null, CurrentUser.name(principal),
                Map.of("flowId", request.flowId(), "trigger", "manual"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("executionId", executionId.toString()));
    }

    static String normalisedDedupKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return null;
        }
        String key = dedupKey.strip();
        if (key.length() > MAX_DEDUP_KEY_LENGTH) {
            throw new IllegalArgumentException("dedupKey is " + key.length()
                    + " characters, exceeding the " + MAX_DEDUP_KEY_LENGTH + "-character limit");
        }
        return key;
    }
}
