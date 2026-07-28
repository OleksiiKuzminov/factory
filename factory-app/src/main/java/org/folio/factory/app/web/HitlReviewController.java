package org.folio.factory.app.web;

import org.folio.factory.app.security.CurrentUser;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/hitl/reviews")
public class HitlReviewController {

    private final HitlReviewRepository reviews;
    private final HitlDecisionService decisionService;
    private final JsonMapper jsonMapper;

    public HitlReviewController(HitlReviewRepository reviews, HitlDecisionService decisionService,
                                JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.decisionService = decisionService;
        this.jsonMapper = jsonMapper;
    }

    public record ReviewSummary(UUID id, UUID executionId, String gateId, HitlReviewStatus status,
                                String title, Instant createdAt) {
    }

    public record ReviewDetail(UUID id, UUID executionId, String gateId, HitlReviewStatus status,
                               JsonNode reviewPackage, String decision, String reviewer, String comments,
                               Instant createdAt, Instant decidedAt) {
    }

    /**
     * No reviewer field by design: the identity recorded in the ledger comes from the
     * authenticated principal, so a client cannot decide a gate under someone else's name.
     */
    public record DecisionRequest(HitlDecision decision, String comments,
                                  Map<String, String> amendedArtifacts) {
    }

    @GetMapping
    public PageResponse<ReviewSummary> list(
            @RequestParam(name = "status", defaultValue = "PENDING") String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Page<HitlReview> result;
        if ("ALL".equalsIgnoreCase(status)) {
            result = reviews.findAll(PageValidation.pageable(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            result = reviews.findByStatus(parseStatus(status),
                    PageValidation.pageable(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));
        }
        return PageResponse.of(result, this::toSummary);
    }

    @GetMapping("/{id}")
    public ReviewDetail get(@PathVariable("id") UUID id) {
        HitlReview review = reviews.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No HITL review " + id));
        return new ReviewDetail(review.getId(), review.getExecutionId(), review.getGateId(), review.getStatus(),
                jsonMapper.readTree(review.getReviewPackage()), review.getDecision(), review.getReviewer(),
                review.getComments(), review.getCreatedAt(), review.getDecidedAt());
    }

    @PostMapping("/{id}/decision")
    public ReviewDetail decide(@PathVariable("id") UUID id, @RequestBody DecisionRequest request,
                               Principal principal) {
        if (request.decision() == null) {
            throw new IllegalArgumentException("decision is required (APPROVE, AMEND or REJECT)");
        }
        decisionService.decide(id, request.decision(), CurrentUser.name(principal), request.comments(),
                request.amendedArtifacts());
        return get(id);
    }

    private HitlReviewStatus parseStatus(String status) {
        try {
            return HitlReviewStatus.valueOf(status.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown review status '" + status + "'");
        }
    }

    private ReviewSummary toSummary(HitlReview review) {
        String title = jsonMapper.readTree(review.getReviewPackage()).path("title").asString();
        return new ReviewSummary(review.getId(), review.getExecutionId(), review.getGateId(),
                review.getStatus(), title, review.getCreatedAt());
    }
}
