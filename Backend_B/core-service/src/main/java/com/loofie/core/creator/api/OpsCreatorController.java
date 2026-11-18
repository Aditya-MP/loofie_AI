package com.loofie.core.creator.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.creator.app.CreatorProfileService;
import com.loofie.core.creator.app.OpsCreatorService;
import com.loofie.core.creator.domain.CreatorProfile;
import com.loofie.core.creator.domain.MetricValue;
import com.loofie.core.job.domain.Actor;

/** Ops console: creator verification queue and passport decisions (round C). Staff only; every call is audited. */
@RestController
@RequestMapping("/api/core/ops/creators")
class OpsCreatorController {

    record ReviewRequest(Boolean accept, String note) {
    }

    record MetricRequest(MetricValue.Metric metric, Long value, String reason) {
    }

    record LevelRequest(CreatorProfile.Level level, String reason) {
    }

    record BlockRequest(Boolean blocked, String reason) {
    }

    private final OpsCreatorService ops;

    OpsCreatorController(OpsCreatorService ops) {
        this.ops = ops;
    }

    @GetMapping("/evidence-queue")
    List<OpsCreatorService.QueueItem> queue(Actor actor, @RequestParam(required = false) Instant after,
                                            @RequestParam(defaultValue = "50") int limit) {
        return ops.queue(actor, after, limit);
    }

    /** A 5-minute link to look at the screenshot (audited). */
    @GetMapping("/evidence/{evidenceId}/link")
    OpsCreatorService.EvidenceLink link(Actor actor, @PathVariable UUID evidenceId) {
        return ops.open(actor, evidenceId);
    }

    @PutMapping("/evidence/{evidenceId}")
    ResponseEntity<Void> review(Actor actor, @PathVariable UUID evidenceId, @RequestBody ReviewRequest body) {
        ops.review(actor, evidenceId, body != null && Boolean.TRUE.equals(body.accept()),
                body == null ? null : body.note());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{creatorId}")
    CreatorProfileService.ProfileView view(Actor actor, @PathVariable UUID creatorId) {
        return ops.view(actor, creatorId);
    }

    @PutMapping("/{creatorId}/metrics")
    CreatorProfileService.ProfileView metric(Actor actor, @PathVariable UUID creatorId,
                                             @RequestBody MetricRequest body) {
        return ops.checkMetric(actor, creatorId, body == null ? null : body.metric(), body == null ? null
                : body.value(), body == null ? null : body.reason());
    }

    @PutMapping("/{creatorId}/level")
    CreatorProfileService.ProfileView level(Actor actor, @PathVariable UUID creatorId, @RequestBody LevelRequest body) {
        return ops.setLevel(actor, creatorId, body == null ? null : body.level(), body == null ? null : body.reason());
    }

    @PutMapping("/{creatorId}/block")
    CreatorProfileService.ProfileView block(Actor actor, @PathVariable UUID creatorId, @RequestBody BlockRequest body) {
        return ops.block(actor, creatorId, body == null || body.blocked() == null || body.blocked(),
                body == null ? null : body.reason());
    }
}
