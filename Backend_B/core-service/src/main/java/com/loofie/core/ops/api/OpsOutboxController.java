package com.loofie.core.ops.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.messaging.OutboxAdmin;
import com.loofie.core.messaging.OutboxAdmin.Parked;

/** Events Backend A refused for good, and resending them once fixed. HTTP mapping only; rules in {@link OutboxAdmin}. */
@RestController
@RequestMapping("/api/core/ops/outbox/parked")
class OpsOutboxController {

    private final OutboxAdmin outbox;

    OpsOutboxController(OutboxAdmin outbox) {
        this.outbox = outbox;
    }

    @GetMapping
    List<Parked> parked(Actor actor, @RequestParam(defaultValue = "100") int limit) {
        return outbox.parked(actor, limit);
    }

    /** 204 when requeued; 404 when it is not (or no longer) parked. Repeating it is harmless. */
    @PostMapping("/{id}/requeue")
    ResponseEntity<Void> requeue(Actor actor, @PathVariable UUID id) {
        return outbox.requeue(actor, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
