package com.loofie.core.job.app;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.loofie.core.identity.app.CompanyWork;
import com.loofie.core.job.infra.JobReadRepository;

/** Answers identity's question about a company's open jobs (account deletion of its last member). */
@Component
class CompanyWorkQuery implements CompanyWork {

    private final JobReadRepository reads;

    CompanyWorkQuery(JobReadRepository reads) {
        this.reads = reads;
    }

    @Override
    public List<UUID> activeJobIds(UUID orgId) {
        return reads.activeJobIdsForOrg(orgId);
    }
}
