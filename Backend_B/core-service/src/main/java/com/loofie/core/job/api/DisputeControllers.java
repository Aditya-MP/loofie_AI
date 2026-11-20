package com.loofie.core.job.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.DisputeService;
import com.loofie.core.job.app.SwapOfferService;
import com.loofie.core.job.app.TransitionResult;
import com.loofie.core.job.domain.Actor;

/** F9 routes: ops offers a replacement creator, the brand decides, the creator states her side of a dispute. */
@RestController
class DisputeControllers {

    record OfferRequest(UUID creatorId, String note, String suggestionId) {
    }

    record ReasonRequest(String reason) {
    }

    record StatementRequest(String statement) {
    }

    private final SwapOfferService swaps;
    private final DisputeService disputes;

    DisputeControllers(SwapOfferService swaps, DisputeService disputes) {
        this.swaps = swaps;
        this.disputes = disputes;
    }

    // ---- ops

    @PostMapping("/api/core/ops/jobs/{jobId}/swap-offers")
    ResponseEntity<SwapOfferService.OfferView> offer(Actor actor, @PathVariable UUID jobId, @RequestBody OfferRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(swaps.offer(actor, jobId,
                body == null ? null : body.creatorId(), body == null ? null : body.note(),
                body == null ? null : body.suggestionId()));
    }

    @PostMapping("/api/core/ops/jobs/{jobId}/swap-offers/withdraw")
    ResponseEntity<Void> withdraw(Actor actor, @PathVariable UUID jobId, @RequestBody ReasonRequest body) {
        swaps.withdraw(actor, jobId, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/core/ops/jobs/{jobId}/swap-offers")
    List<SwapOfferService.OfferView> offers(Actor actor, @PathVariable UUID jobId) {
        return swaps.forOps(actor, jobId);
    }

    /** The creator's statement, the review deadline and the offers; videos and the brand's answers: ops drafts view. */
    @GetMapping("/api/core/ops/jobs/{jobId}/dispute")
    DisputeService.OpsDispute dispute(Actor actor, @PathVariable UUID jobId) {
        return disputes.opsView(actor, jobId);
    }

    // ---- brand

    @GetMapping("/api/core/brand/jobs/{jobId}/swap-offer")
    SwapOfferService.BrandOffer brandOffer(Actor actor, @PathVariable UUID jobId) {
        return swaps.brandView(actor, jobId);
    }

    @PostMapping("/api/core/brand/jobs/{jobId}/swap-offer/approve")
    TransitionResult approve(Actor actor, @PathVariable UUID jobId) {
        return swaps.approve(actor, jobId);
    }

    @PostMapping("/api/core/brand/jobs/{jobId}/swap-offer/decline")
    ResponseEntity<Void> decline(Actor actor, @PathVariable UUID jobId, @RequestBody ReasonRequest body) {
        swaps.decline(actor, jobId, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    // ---- creator

    @PostMapping("/api/core/creator/jobs/{jobId}/dispute-statement")
    ResponseEntity<DisputeService.StatementView> statement(Actor actor, @PathVariable UUID jobId, @RequestBody StatementRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(disputes.statement(actor, jobId,
                body == null ? null : body.statement()));
    }
}
