package com.loofie.core.refund.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.common.web.Idempotency;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobAction;
import com.loofie.core.refund.app.RefundService;
import com.loofie.core.refund.app.RefundService.RefundDetail;
import com.loofie.core.refund.app.RefundService.RefundView;
import com.loofie.core.refund.app.RefundSummary;
import com.loofie.core.refund.domain.RefundReason;

import tools.jackson.databind.json.JsonMapper;

/**
 * Refunds and cancellations: request, then approve (DECISIONS.md section 6). Money moves, so every write needs an
 * {@code Idempotency-Key}: a retried "approve" must never approve twice. HTTP mapping only; rules in {@link RefundService}.
 */
@RestController
@RequestMapping("/api/core/ops")
class OpsRefundController {

    record RequestBodyIn(@NotNull JobAction action, @NotNull RefundReason reasonCode,
                         @NotNull @Size(min = 1, max = 1000) String note) {
    }

    record DecisionIn(@Size(max = 1000) String note) {
    }

    private final RefundService refunds;
    private final RefundSummary summary;
    private final Idempotency idempotency;
    private final JsonMapper json;

    OpsRefundController(RefundService refunds, RefundSummary summary, Idempotency idempotency, JsonMapper json) {
        this.refunds = refunds;
        this.summary = summary;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/jobs/{jobId}/refund-requests")
    ResponseEntity<RefundView> request(Actor actor, @PathVariable UUID jobId,
                                       @RequestHeader(value = Idempotency.HEADER, required = false) String key,
                                       @Valid @RequestBody RequestBodyIn body) {
        RefundView v = once(actor, key, "POST /jobs/{jobId}/refund-requests", jobId + json.writeValueAsString(body),
                () -> refunds.request(actor, jobId, body.action(), body.reasonCode(), body.note()));
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @PostMapping("/refund-requests/{id}/approve")
    RefundView approve(Actor actor, @PathVariable UUID id,
                       @RequestHeader(value = Idempotency.HEADER, required = false) String key,
                       @Valid @RequestBody(required = false) DecisionIn body) {
        String note = body == null ? null : body.note();
        return once(actor, key, "POST /refund-requests/{id}/approve", id + String.valueOf(note),
                () -> refunds.approve(actor, id, note));
    }

    @PostMapping("/refund-requests/{id}/reject")
    RefundView reject(Actor actor, @PathVariable UUID id,
                      @RequestHeader(value = Idempotency.HEADER, required = false) String key,
                      @Valid @RequestBody DecisionIn body) {
        return once(actor, key, "POST /refund-requests/{id}/reject", id + String.valueOf(body.note()),
                () -> refunds.reject(actor, id, body.note()));
    }

    @PostMapping("/refund-requests/{id}/withdraw")
    RefundView withdraw(Actor actor, @PathVariable UUID id,
                        @RequestHeader(value = Idempotency.HEADER, required = false) String key) {
        return once(actor, key, "POST /refund-requests/{id}/withdraw", id.toString(), () -> refunds.withdraw(actor, id));
    }

    @GetMapping("/refund-requests")
    List<RefundView> open(Actor actor, @RequestParam(defaultValue = "50") int limit) {
        return refunds.open(actor, limit);
    }

    @GetMapping("/refund-requests/{id}")
    RefundDetail detail(Actor actor, @PathVariable UUID id) {
        return refunds.detail(actor, id);
    }

    @GetMapping("/refunds/summary")
    RefundSummary.Day day(Actor actor, @RequestParam LocalDate date) {
        return summary.day(actor, date);
    }

    private RefundView once(Actor actor, String key, String route, String body, java.util.function.Supplier<RefundView> action) {
        if (key == null) {
            throw new Idempotency.KeyRequiredException(route);
        }
        return idempotency.run(actor.role().name() + ":" + actor.userId(), key, Idempotency.hash(route, body),
                RefundView.class, action).value();
    }
}
