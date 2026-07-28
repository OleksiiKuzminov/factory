package org.folio.factory.app.web;

import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook receivers that adapt external event formats to the internal
 * {@link TriggerEvent} model.
 *
 * <p>Authentication is a shared secret sent in the {@code X-Factory-Webhook-Token}
 * header; the legacy {@code ?token=} query parameter is still accepted but leaks into
 * access, proxy and Referer logs. GitHub deliveries additionally carry an HMAC-SHA256
 * signature over the raw body, verified whenever {@code factory.webhooks.github-secret}
 * is configured. Both comparisons are constant-time.</p>
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    static final String TOKEN_HEADER = "X-Factory-Webhook-Token";
    static final String GITHUB_SIGNATURE_HEADER = "X-Hub-Signature-256";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PipelineRouter router;
    private final JsonMapper jsonMapper;
    private final String sharedSecret;
    private final String gitHubSecret;

    public WebhookController(PipelineRouter router, JsonMapper jsonMapper,
                             @Value("${factory.webhooks.shared-secret:}") String sharedSecret,
                             @Value("${factory.webhooks.github-secret:}") String gitHubSecret) {
        this.router = router;
        this.jsonMapper = jsonMapper;
        this.sharedSecret = sharedSecret;
        this.gitHubSecret = gitHubSecret;
        if (sharedSecret.isBlank()) {
            log.warn("Webhook endpoints are UNAUTHENTICATED: set factory.webhooks.shared-secret "
                    + "(FACTORY_WEBHOOKS_SHARED_SECRET) in any non-local deployment");
        }
    }

    @PostMapping("/jira")
    public ResponseEntity<Map<String, List<String>>> jira(
            @RequestHeader(name = TOKEN_HEADER, required = false) String headerToken,
            @RequestParam(name = "token", required = false) String queryToken,
            @RequestBody JsonNode body) {
        checkSecret(headerToken, queryToken);
        String webhookEvent = body.path("webhookEvent").asString("");
        String type = switch (webhookEvent) {
            case "jira:issue_updated" -> "jira.issue.transitioned";
            case "jira:issue_created" -> "jira.issue.created";
            default -> "jira.event";
        };
        return respond(router.route(TriggerEvent.of(type, "jira-webhook", normaliseJiraPayload(body))));
    }

    /**
     * Raw Jira webhook bodies carry the key at {@code issue.key}; flow input
     * schemas declare the internal contract field {@code issueKey}. Normalising
     * external formats to the internal contract is this adapter's job.
     */
    private JsonNode normaliseJiraPayload(JsonNode body) {
        if (body.isObject() && !body.has("issueKey")) {
            String issueKey = body.path("issue").path("key").asString("");
            if (!issueKey.isBlank()) {
                ((ObjectNode) body).put("issueKey", issueKey);
            }
        }
        return body;
    }

    /**
     * Takes the body as bytes and parses it here: GitHub's signature covers the exact
     * octets it sent, which a re-serialized {@link JsonNode} would no longer match.
     */
    @PostMapping("/github")
    public ResponseEntity<Map<String, List<String>>> github(
            @RequestHeader(name = TOKEN_HEADER, required = false) String headerToken,
            @RequestParam(name = "token", required = false) String queryToken,
            @RequestHeader(name = GITHUB_SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = "X-GitHub-Event", defaultValue = "event") String event,
            @RequestBody byte[] body) {
        checkSecret(headerToken, queryToken);
        checkGitHubSignature(signature, body);
        return respond(router.route(TriggerEvent.of("github." + event, "github-webhook", parse(body))));
    }

    private JsonNode parse(byte[] body) {
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            // Same contract as the converter-parsed endpoints; parser internals stay internal.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed request body");
        }
    }

    private void checkSecret(String headerToken, String queryToken) {
        if (sharedSecret.isBlank()) {
            return;
        }
        String presented = headerToken;
        if (presented == null && queryToken != null) {
            log.warn("Webhook used the deprecated ?token= query parameter, which leaks into access and "
                    + "proxy logs; send the shared secret in the {} header instead", TOKEN_HEADER);
            presented = queryToken;
        }
        if (presented == null || !constantTimeEquals(sharedSecret, presented)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid webhook token");
        }
    }

    private void checkGitHubSignature(String signature, byte[] body) {
        if (gitHubSecret.isBlank()) {
            return;
        }
        if (signature == null || signature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + GITHUB_SIGNATURE_HEADER);
        }
        if (!constantTimeEquals("sha256=" + hmacSha256Hex(body), signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid " + GITHUB_SIGNATURE_HEADER);
        }
    }

    private String hmacSha256Hex(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(gitHubSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot compute the GitHub webhook signature", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Map<String, List<String>>> respond(List<UUID> executionIds) {
        if (executionIds.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("executionIds", executionIds.stream().map(UUID::toString).toList()));
    }
}
