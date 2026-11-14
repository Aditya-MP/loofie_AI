package com.loofie.core.ops.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.JobQueryService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobStatus;
import com.loofie.core.job.read.JobViews.JobDetail;
import com.loofie.core.job.read.JobViews.JobFilter;
import com.loofie.core.job.read.JobViews.JobSummary;
import com.loofie.core.job.read.JobViews.Page;

/**
 * F10 ops console, read side: every job across all brands, filterable, plus a job's full timeline.
 * Ops actions go through {@code POST /api/core/jobs/{id}/transitions} with a mandatory reason.
 * HTTP mapping only: the OPS-only check is in {@link JobQueryService}.
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
        return queries.list(actor, new JobFilter(status, brandOrgId, orderId, overdue), page, size);
    }

    @GetMapping("/{jobId}")
    JobDetail detail(Actor actor, @PathVariable UUID jobId) {
        return queries.detail(actor, jobId);
    }
}
