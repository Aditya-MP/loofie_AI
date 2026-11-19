package com.loofie.core.draft.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.loofie.core.common.web.RequestIdFilter;
import com.loofie.core.draft.app.BrandDraftService;
import com.loofie.core.draft.app.CreatorDraftService;
import com.loofie.core.draft.app.DraftViews;
import com.loofie.core.draft.app.OpsDraftService;
import com.loofie.core.draft.domain.Brief;
import com.loofie.core.job.domain.Actor;

/** F7 HTTP endpoints. Controllers only map requests; rules and permissions live in the app services. */
final class DraftControllers {

    private DraftControllers() {
    }

    /** The creator: upload link, submit, status of her videos and what the brand asked to change. */
    @RestController
    @RequestMapping("/api/core/creator/jobs/{jobId}/drafts")
    static class CreatorDrafts {

        /** @param sizeBytes the video's exact size: the signed URL accepts only that size */
        record TicketRequest(@NotNull @Size(max = 40) String contentType, Long sizeBytes) {
        }

        record SubmitRequest(@NotNull @Size(max = 200) String key) {
        }

        private final CreatorDraftService drafts;

        CreatorDrafts(CreatorDraftService drafts) {
            this.drafts = drafts;
        }

        @PostMapping("/upload")
        CreatorDraftService.UploadTicket ticket(Actor actor, @PathVariable UUID jobId,
                                                @Valid @RequestBody TicketRequest body) {
            return drafts.uploadTicket(actor, jobId, body.contentType(), body.sizeBytes());
        }

        @PostMapping
        ResponseEntity<DraftViews.Draft> submit(Actor actor, @PathVariable UUID jobId,
                                                @Valid @RequestBody SubmitRequest body) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(drafts.submit(actor, jobId, body.key()));
        }

        @GetMapping
        DraftViews.CreatorDrafts view(Actor actor, @PathVariable UUID jobId) {
            return drafts.view(actor, jobId);
        }
    }

    /** The brand: preview and checklist, decision, full-quality download after approval, rating. */
    @RestController
    @RequestMapping("/api/core/brand/jobs/{jobId}/draft")
    static class BrandDrafts {

        record AnswerRequest(@NotNull @Size(max = 32) String key, Boolean ok, @Size(max = 500) String note) {
        }

        record ReviewRequest(@NotNull UUID draftId, @NotNull BrandDraftService.Decision decision,
                             @Size(max = 12) List<@Valid AnswerRequest> answers, @Size(max = 1000) String note,
                             Long expectedVersion) {
        }

        record RatingRequest(@NotNull Integer stars, @Size(max = 1000) String comment) {
        }

        private final BrandDraftService drafts;

        BrandDrafts(BrandDraftService drafts) {
            this.drafts = drafts;
        }

        @GetMapping
        DraftViews.BrandDraft view(Actor actor, @PathVariable UUID jobId) {
            return drafts.view(actor, jobId);
        }

        @PostMapping("/review")
        DraftViews.BrandDraft review(Actor actor, @PathVariable UUID jobId, @Valid @RequestBody ReviewRequest body) {
            List<BrandDraftService.AnswerInput> answers = body.answers() == null ? List.of() : body.answers().stream()
                    .map(a -> new BrandDraftService.AnswerInput(a.key(), a.ok(), a.note())).toList();
            return drafts.review(actor, jobId, new BrandDraftService.ReviewInput(body.draftId(), body.decision(),
                    answers, body.note(), body.expectedVersion()));
        }

        @PostMapping("/master")
        DraftViews.Link master(Actor actor, @PathVariable UUID jobId) {
            return drafts.master(actor, jobId);
        }

        @PostMapping("/rating")
        DraftViews.BrandDraft rate(Actor actor, @PathVariable UUID jobId, @Valid @RequestBody RatingRequest body) {
            return drafts.rate(actor, jobId, body.stars(), body.comment());
        }
    }

    /** Ops: brief copy (D1), every video with its checks, open one for a dispute. */
    @RestController
    @RequestMapping("/api/core/ops/jobs/{jobId}")
    static class OpsDrafts {

        record ItemRequest(@NotNull @Size(max = 32) String key, @NotNull @Size(max = 200) String label) {
        }

        record BriefRequest(@NotNull @Size(min = 1, max = 12) List<@Valid ItemRequest> checklist,
                            @NotNull Integer minSeconds, @NotNull Integer maxSeconds, Integer adRightsDays,
                            @NotNull @Size(max = 500) String reason) {
        }

        record OpenRequest(@NotNull @Size(max = 500) String reason) {
        }

        private final OpsDraftService drafts;

        OpsDrafts(OpsDraftService drafts) {
            this.drafts = drafts;
        }

        @PutMapping("/brief")
        DraftViews.BriefView brief(Actor actor, @PathVariable UUID jobId, @Valid @RequestBody BriefRequest body) {
            Brief brief = new Brief(body.checklist().stream().map(i -> new Brief.ChecklistItem(i.key(), i.label()))
                    .toList(), body.minSeconds(), body.maxSeconds(), body.adRightsDays(), true);
            return drafts.setBrief(actor, jobId, brief, body.reason());
        }

        @GetMapping("/drafts")
        OpsDraftService.OpsDrafts view(Actor actor, @PathVariable UUID jobId) {
            return drafts.view(actor, jobId);
        }

        @PostMapping("/drafts/{draftId}/open")
        DraftViews.Link open(Actor actor, @PathVariable UUID jobId, @PathVariable UUID draftId,
                             @RequestParam(defaultValue = "false") boolean master,
                             @Valid @RequestBody OpenRequest body) {
            return drafts.open(actor, jobId, draftId, master, body.reason());
        }
    }

    /** A brief that breaks the rules (bad key, duration, too many items): 422. */
    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Errors {

        @ExceptionHandler(Brief.InvalidBriefException.class)
        ProblemDetail invalidBrief(Brief.InvalidBriefException e) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
            p.setType(URI.create("https://loofie.app/problems/invalid-brief"));
            if (RequestIdFilter.current() != null) {
                p.setProperty("requestId", RequestIdFilter.current().toString());
            }
            return p;
        }
    }
}
