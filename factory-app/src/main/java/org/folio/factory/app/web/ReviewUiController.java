package org.folio.factory.app.web;

import org.folio.factory.app.security.CurrentUser;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Server-rendered HITL console: a review inbox and a review detail page with
 * inline artifact editing. Plain HTML forms post against the same decision
 * service the REST API uses.
 */
@Controller
public class ReviewUiController {

    private static final String ARTIFACT_FIELD_PREFIX = "artifact:";

    private final HitlReviewRepository reviews;
    private final HitlDecisionService decisionService;
    private final JsonMapper jsonMapper;

    public ReviewUiController(HitlReviewRepository reviews, HitlDecisionService decisionService,
                              JsonMapper jsonMapper) {
        this.reviews = reviews;
        this.decisionService = decisionService;
        this.jsonMapper = jsonMapper;
    }

    private static final int PAGE_SIZE = 50;
    private static final List<String> STATUS_TABS =
            List.of("PENDING", "APPROVED", "AMENDED", "REJECTED", "ALL");

    @GetMapping("/reviews")
    public String reviews(@RequestParam(name = "status", defaultValue = "PENDING") String status,
                          @RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        String selected = normaliseStatus(status);
        Page<HitlReview> paged = null;
        List<HitlReview> rows;
        if ("PENDING".equals(selected)) {
            // The inbox stays unpaged by design: it is bounded and worked top-down.
            rows = reviews.findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING);
        } else {
            Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE,
                    Sort.by(Sort.Direction.DESC, "createdAt"));
            paged = "ALL".equals(selected)
                    ? reviews.findAll(pageable)
                    : reviews.findByStatus(HitlReviewStatus.valueOf(selected), pageable);
            rows = paged.getContent();
        }
        model.addAttribute("reviews", rows.stream().map(this::reviewRow).toList());
        model.addAttribute("statusTabs", STATUS_TABS);
        model.addAttribute("selectedStatus", selected);
        model.addAttribute("showDecision", !"PENDING".equals(selected));
        model.addAttribute("page", paged);
        model.addAttribute("baseUrl", "/reviews?status=" + selected);
        return "reviews";
    }

    private static String normaliseStatus(String status) {
        if (status == null || status.isBlank()) {
            return "PENDING";
        }
        String upper = status.strip().toUpperCase();
        return STATUS_TABS.contains(upper) ? upper : "PENDING";
    }

    @GetMapping("/reviews/{id}")
    public String review(@PathVariable("id") UUID id,
                         @RequestParam(name = "error", required = false) String error, Model model) {
        HitlReview review = reviews.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No HITL review " + id));
        JsonNode reviewPackage = jsonMapper.readTree(review.getReviewPackage());
        model.addAttribute("review", review);
        model.addAttribute("pkg", reviewPackage);
        model.addAttribute("error", error);
        return "review";
    }

    @PostMapping("/reviews/{id}/decision")
    public String decide(@PathVariable("id") UUID id,
                         @RequestParam("decision") String decision,
                         @RequestParam(name = "comments", required = false) String comments,
                         @RequestParam Map<String, String> allParams,
                         Principal principal,
                         RedirectAttributes redirect) {
        // Never a form field: the ledger's reviewer is the signed-in user.
        String reviewer = CurrentUser.name(principal);
        // The whole form processing sits inside one try so validation failures
        // redirect back to the review page instead of falling through to the
        // REST API's JSON exception handler. Unchanged-artifact filtering happens
        // in HitlDecisionService so REST callers get the same behavior.
        try {
            HitlDecision hitlDecision = HitlDecision.valueOf(decision);
            Map<String, String> amendments = new LinkedHashMap<>();
            if (hitlDecision == HitlDecision.AMEND) {
                for (Map.Entry<String, String> param : allParams.entrySet()) {
                    if (param.getKey().startsWith(ARTIFACT_FIELD_PREFIX)) {
                        amendments.put(param.getKey().substring(ARTIFACT_FIELD_PREFIX.length()),
                                param.getValue());
                    }
                }
            }
            decisionService.decide(id, hitlDecision, reviewer, comments, amendments);
            redirect.addFlashAttribute("message", "Decision " + hitlDecision + " recorded for review " + id);
            return "redirect:/reviews";
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            return UiFormat.errorRedirect("/reviews/" + id, e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            // HitlReview is @Version-ed: a concurrent reviewer's decision committed first.
            return UiFormat.errorRedirect("/reviews/" + id,
                    "This review was decided concurrently; reload to see the outcome");
        }
    }

    private Map<String, Object> reviewRow(HitlReview review) {
        JsonNode reviewPackage = jsonMapper.readTree(review.getReviewPackage());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", review.getId());
        row.put("title", reviewPackage.path("title").asString());
        row.put("flowId", reviewPackage.path("flowId").asString());
        row.put("gateId", review.getGateId());
        row.put("createdAt", UiFormat.format(review.getCreatedAt()));
        row.put("status", review.getStatus());
        row.put("decision", review.getDecision());
        row.put("reviewer", review.getReviewer());
        row.put("decidedAt", UiFormat.format(review.getDecidedAt()));
        return row;
    }
}
