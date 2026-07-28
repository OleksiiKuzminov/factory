package org.folio.factory.app.web;

import org.folio.factory.app.security.CurrentUser;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HitlReviewControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Principal QA_LEAD = () -> "qa-lead";

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private HitlReviewRepository reviews;

    @Mock
    private HitlDecisionService decisionService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new HitlReviewController(reviews, decisionService, json))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private HitlReview pendingReview() {
        return new HitlReview(EXECUTION_ID, "qa-gate-1", 2, "{\"title\":\"Review test plan\"}");
    }

    private Page<HitlReview> pageOf(HitlReview review) {
        return new PageImpl<>(List.of(review), PageRequest.of(0, 50), 1);
    }

    @Test
    void get_unknownId_notFound() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(reviews.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/hitl/reviews/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("No HITL review")));
    }

    @Test
    void list_bogusStatus_unprocessableWithoutRepositoryAccess() throws Exception {
        mvc.perform(get("/api/hitl/reviews").param("status", "BOGUS"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("BOGUS")));

        verifyNoInteractions(reviews, decisionService);
    }

    @Test
    void list_statusAll_usesFindAllDescendingAndWrapsEnvelope() throws Exception {
        HitlReview review = pendingReview();
        when(reviews.findAll(any(Pageable.class))).thenReturn(pageOf(review));

        mvc.perform(get("/api/hitl/reviews").param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(review.getId().toString()))
                .andExpect(jsonPath("$.items[0].executionId").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.items[0].gateId").value("qa-gate-1"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].title").value("Review test plan"))
                .andExpect(jsonPath("$.totalElements").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviews).findAll(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void list_specificStatus_usesPagedFinderWithAscendingSort() throws Exception {
        HitlReview review = pendingReview();
        when(reviews.findByStatus(eq(HitlReviewStatus.PENDING), any(Pageable.class))).thenReturn(pageOf(review));

        mvc.perform(get("/api/hitl/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(review.getId().toString()))
                .andExpect(jsonPath("$.items[0].title").value("Review test plan"));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviews).findByStatus(eq(HitlReviewStatus.PENDING), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Test
    void list_sizeTooLarge_unprocessable() throws Exception {
        mvc.perform(get("/api/hitl/reviews").param("size", "201"))
                .andExpect(status().is(422));
        verifyNoInteractions(reviews);
    }

    @Test
    void decide_missingDecision_unprocessableWithoutDelegation() throws Exception {
        mvc.perform(post("/api/hitl/reviews/{id}/decision", EXECUTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("decision is required")));

        verifyNoInteractions(decisionService);
    }

    @Test
    void decide_alreadyDecided_conflict() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        when(decisionService.decide(id, HitlDecision.APPROVE, "qa-lead", null, null))
                .thenThrow(new IllegalStateException("Review " + id + " has already been decided (APPROVED)"));

        mvc.perform(post("/api/hitl/reviews/{id}/decision", id)
                        .principal(QA_LEAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already been decided")));
    }

    @Test
    void decide_concurrentDecision_conflict() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        when(decisionService.decide(id, HitlDecision.APPROVE, "qa-lead", null, null))
                .thenThrow(new OptimisticLockingFailureException("row was updated by another transaction"));

        mvc.perform(post("/api/hitl/reviews/{id}/decision", id)
                        .principal(QA_LEAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("another transaction")));
    }

    @Test
    void decide_happyPath_delegatesAndReturnsDetail() throws Exception {
        HitlReview review = pendingReview();
        review.decide(HitlReviewStatus.APPROVED, "APPROVE", "qa-lead", "looks good", null);
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));

        mvc.perform(post("/api/hitl/reviews/{id}/decision", review.getId())
                        .principal(QA_LEAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comments\":\"looks good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(review.getId().toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.reviewer").value("qa-lead"))
                .andExpect(jsonPath("$.comments").value("looks good"))
                .andExpect(jsonPath("$.reviewPackage.title").value("Review test plan"));

        verify(decisionService).decide(review.getId(), HitlDecision.APPROVE, "qa-lead", "looks good", null);
    }

    @Test
    void decide_reviewerFieldInBody_ignoredInFavourOfAuthenticatedPrincipal() throws Exception {
        HitlReview review = pendingReview();
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));

        mvc.perform(post("/api/hitl/reviews/{id}/decision", review.getId())
                        .principal(QA_LEAD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewer\":\"someone-else\"}"))
                .andExpect(status().isOk());

        verify(decisionService).decide(review.getId(), HitlDecision.APPROVE, "qa-lead", null, null);
    }

    @Test
    void decide_withoutPrincipal_recordsAnonymousRatherThanAnyClientSuppliedName() throws Exception {
        HitlReview review = pendingReview();
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));

        mvc.perform(post("/api/hitl/reviews/{id}/decision", review.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reviewer\":\"someone-else\"}"))
                .andExpect(status().isOk());

        verify(decisionService).decide(review.getId(), HitlDecision.APPROVE, CurrentUser.ANONYMOUS, null, null);
    }
}
