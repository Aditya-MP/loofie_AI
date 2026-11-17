package com.loofie.core.job.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.JobQueryService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.read.JobViews.DeliveryView;

/**
 * F6 delivery screen: "your parcel" for the creator, "what the creator reported" for the brand. Only the job's brand
 * and its current creator (and ops); anyone else gets 404, as for a missing job.
 */
@RestController
class JobDeliveryController {

    private final JobQueryService queries;

    JobDeliveryController(JobQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/core/jobs/{jobId}/delivery")
    DeliveryView delivery(Actor actor, @PathVariable UUID jobId) {
        return queries.delivery(actor, jobId);
    }
}
