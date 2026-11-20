package com.loofie.core.ops.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.OpsTimeService;
import com.loofie.core.job.app.PayoutHoldService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.ops.app.OpsOverviewService;

/** F10 ops console: the overview, the time log and payout holds. Permission checks live in the services. */
@RestController
@RequestMapping("/api/core/ops")
class OpsConsoleController {

    record TimeRequest(UUID jobId, UUID orderId, Integer minutes, String activity, String note) {
    }

    record ReasonRequest(String reason) {
    }

    private final OpsOverviewService overview;
    private final OpsTimeService time;
    private final PayoutHoldService holds;

    OpsConsoleController(OpsOverviewService overview, OpsTimeService time, PayoutHoldService holds) {
        this.overview = overview;
        this.time = time;
        this.holds = holds;
    }

    /** What needs a person now and how ops is doing; up to 30 s old ({@code asOf}). */
    @GetMapping("/overview")
    OpsOverviewService.Overview overview(Actor actor) {
        return overview.overview(actor);
    }

    // ---- time log

    @PostMapping("/time")
    ResponseEntity<OpsTimeService.EntryView> log(Actor actor, @RequestBody TimeRequest body) {
        if (body == null) {
            body = new TimeRequest(null, null, null, null, null);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(time.log(actor, body.jobId(), body.orderId(),
                body.minutes(), body.activity(), body.note()));
    }

    /** Reverses one entry (append-only: nothing is edited or deleted). */
    @PostMapping("/time/{entryId}/correct")
    ResponseEntity<OpsTimeService.EntryView> correct(Actor actor, @PathVariable UUID entryId,
                                                     @RequestBody ReasonRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(time.correct(actor, entryId,
                body == null ? null : body.reason()));
    }

    @GetMapping("/jobs/{jobId}/time")
    OpsTimeService.TimeSheet jobTime(Actor actor, @PathVariable UUID jobId) {
        return time.forJob(actor, jobId);
    }

    @GetMapping("/orders/{orderId}/time")
    OpsTimeService.TimeSheet orderTime(Actor actor, @PathVariable UUID orderId) {
        return time.forOrder(actor, orderId);
    }

    /** Margin review (ops lead / founder): jobs worked on in [from, to) with their fee and ops minutes. */
    @GetMapping("/time/report")
    List<OpsTimeService.ReportLine> report(Actor actor, @RequestParam Instant from, @RequestParam Instant to) {
        return time.report(actor, from, to);
    }

    // ---- payouts

    @GetMapping("/payouts")
    PayoutHoldService.QueuePage payouts(Actor actor, @RequestParam(defaultValue = "false") boolean held,
                                        @RequestParam(required = false) String after,
                                        @RequestParam(defaultValue = "50") int size) {
        return holds.queue(actor, held, after, size);
    }

    @PostMapping("/jobs/{jobId}/payout-hold")
    ResponseEntity<PayoutHoldService.HoldView> hold(Actor actor, @PathVariable UUID jobId,
                                                    @RequestBody ReasonRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holds.place(actor, jobId,
                body == null ? null : body.reason()));
    }

    @PostMapping("/jobs/{jobId}/payout-hold/release")
    PayoutHoldService.HoldView release(Actor actor, @PathVariable UUID jobId, @RequestBody ReasonRequest body) {
        return holds.release(actor, jobId, body == null ? null : body.reason());
    }

    @GetMapping("/jobs/{jobId}/payout-holds")
    List<PayoutHoldService.HoldView> holdHistory(Actor actor, @PathVariable UUID jobId) {
        return holds.history(actor, jobId);
    }
}
