package com.loofie.core.ops.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.JobQueryService;
import com.loofie.core.job.app.JobViews.JobDetail;
import com.loofie.core.job.app.JobViews.JobFilter;
import com.loofie.core.job.app.JobViews.JobSummary;
import com.loofie.core.job.app.JobViews.Page;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.job.domain.JobStatus;

/**
 * F10 ops console, read side: every job across all brands, filterable, plus a job's full timeline.
 * Ops actions go through {@code POST /api/core/jobs/{id}/transitions} with a mandatory reason.
 */
@RestController
@RequestMapping("/api/core/ops/jobs")
class OpsJobController {

    private final JobQueryService queries;

    OpsJobController(JobQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    Page<JobSummary> list(Actor actor,
                          @RequestParam(required = false) Set<JobStatus> status,
                          @RequestParam(required = false) UUID brandOrgId,
                          @RequestParam(required = false) UUID orderId,
                          @RequestParam(defaultValue = "false") boolean overdue,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "25") int size) {
        requireOps(actor);
        return queries.list(new JobFilter(status, brandOrgId, orderId, overdue), page, size);
    }

    @GetMapping("/{jobId}")
    JobDetail detail(Actor actor, @PathVariable UUID jobId) {
        requireOps(actor);
        return queries.detail(jobId);
    }

    private static void requireOps(Actor actor) {
        if (actor.role() != ActorRole.OPS) {
            throw new ActorNotAllowedException("The ops console is for OPS only");
        }
    }
}
