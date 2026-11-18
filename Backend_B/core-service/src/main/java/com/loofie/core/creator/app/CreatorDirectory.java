package com.loofie.core.creator.app;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.loofie.core.creator.infra.CreatorProfileRepository;
import com.loofie.core.identity.app.AccountDirectory;
import com.loofie.core.job.domain.CreatorEligibility;

/**
 * Whether a creator may be shortlisted (F3) and accept work (job engine, decision C6): account ACTIVE, passport
 * complete, LISTED (not paused by her, not blocked by ops).
 */
@Service
public class CreatorDirectory implements CreatorEligibility {

    private final CreatorProfileRepository profiles;
    private final AccountDirectory accounts;
    private final CreatorProperties props;

    public CreatorDirectory(CreatorProfileRepository profiles, AccountDirectory accounts, CreatorProperties props) {
        this.profiles = profiles;
        this.accounts = accounts;
        this.props = props;
    }

    public boolean bookable(UUID creatorId) {
        return profiles.listed(creatorId) && accounts.active(creatorId);
    }

    @Override
    public boolean mayAcceptWork(UUID creatorId) {
        return !props.requireBookableToAccept() || bookable(creatorId);
    }
}
