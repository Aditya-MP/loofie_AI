package com.loofie.core.job.app;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.loofie.core.identity.app.CreatorWork;
import com.loofie.core.job.infra.JobReadRepository;

/** Answers identity's question about a creator's open jobs (suspension notice to ops). */
@Component
class CreatorWorkQuery implements CreatorWork {

    private final JobReadRepository reads;

    CreatorWorkQuery(JobReadRepository reads) {
        this.reads = reads;
    }

    @Override
    public List<UUID> activeJobIds(UUID creatorId) {
        return reads.activeJobIdsForCreator(creatorId);
    }
}
