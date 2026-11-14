package com.loofie.core.job.domain;

import static com.loofie.core.job.domain.ActorRole.BRAND_ADMIN;
import static com.loofie.core.job.domain.ActorRole.BRAND_MEMBER;
import static com.loofie.core.job.domain.ActorRole.CREATOR;
import static com.loofie.core.job.domain.ActorRole.OPS;
import static com.loofie.core.job.domain.ActorRole.SYSTEM;
import static com.loofie.core.job.domain.JobAction.*;
import static com.loofie.core.job.domain.JobStatus.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The F5 job state machine, as data. Mirrors the "Transitions" table in the Backend B
 * architecture doc. Anything not listed here is illegal and is rejected by the engine.
 *
 * <p>Open policy questions are marked TODO(open-q) and kept deliberately narrow until answered.
 */
public final class TransitionTable {

    private record Key(JobStatus from, JobAction action) {
    }

    private static final Map<Key, TransitionRule> RULES = new LinkedHashMap<>();

    static {
        // Order and payment
        add(DRAFT_BRIEF, SUBMIT_ORDER, AWAITING_PAYMENT, BRAND_ADMIN);
        add(AWAITING_PAYMENT, PAYMENT_CONFIRMED, PAID, SYSTEM);

        // Invitation. TODO(open-q): BRAND_MEMBER approval rights; for now only BRAND_ADMIN approves swaps.
        add(PAID, INVITE, CREATOR_INVITED, OPS);
        add(CREATOR_INVITED, ACCEPT, CREATOR_ACCEPTED, CREATOR);
        add(CREATOR_INVITED, DECLINE, DECLINED, CREATOR);
        add(CREATOR_INVITED, ACCEPT_TIMEOUT, DECLINED, SYSTEM);
        add(DECLINED, APPROVE_SWAP, CREATOR_INVITED, BRAND_ADMIN);

        // Product (F6) or digital-only start
        add(CREATOR_ACCEPTED, MARK_SHIPPED, PRODUCT_SHIPPED, BRAND_ADMIN, BRAND_MEMBER);
        add(CREATOR_ACCEPTED, START_DIGITAL, IN_PRODUCTION, SYSTEM);
        addThen(PRODUCT_SHIPPED, CONFIRM_RECEIVED, PRODUCT_RECEIVED, START_PRODUCTION, CREATOR);
        addThen(PRODUCT_SHIPPED, RECEIPT_FALLBACK, PRODUCT_RECEIVED, START_PRODUCTION, SYSTEM);
        add(PRODUCT_RECEIVED, START_PRODUCTION, IN_PRODUCTION, SYSTEM);

        // Production and review (F7)
        add(IN_PRODUCTION, SUBMIT_DRAFT, DRAFT_SUBMITTED, CREATOR);
        add(IN_PRODUCTION, PRODUCTION_EXPIRED, REPLACEMENT_NEEDED, SYSTEM);
        add(DRAFT_SUBMITTED, PREVIEW_READY, IN_REVIEW, SYSTEM);
        add(IN_REVIEW, APPROVE, APPROVED, BRAND_ADMIN);
        add(IN_REVIEW, AUTO_APPROVE, APPROVED, SYSTEM);
        add(IN_REVIEW, REQUEST_REVISION, REVISION_REQUESTED, BRAND_ADMIN);
        add(REVISION_REQUESTED, RESUBMIT, DRAFT_SUBMITTED, CREATOR);
        add(IN_REVIEW, REJECT, DISPUTED, BRAND_ADMIN);

        // Disputes and replacement (F9)
        add(DISPUTED, RESOLVE_APPROVE, APPROVED, OPS);
        add(DISPUTED, RESOLVE_REPLACE, REPLACEMENT_NEEDED, OPS);
        add(DISPUTED, RESOLVE_REFUND, REFUNDED, OPS);
        add(REPLACEMENT_NEEDED, APPROVE_SWAP, CREATOR_INVITED, BRAND_ADMIN);
        add(REPLACEMENT_NEEDED, REPLACEMENT_EXPIRED, REFUNDED, SYSTEM);

        // Payout (facts from finance) and close. TODO(open-q): what triggers CLOSE.
        add(APPROVED, PAYOUT_QUEUED, PAYOUT_PENDING, SYSTEM);
        add(PAYOUT_PENDING, PAYOUT_COMPLETED, PAID_OUT, SYSTEM);
        add(PAID_OUT, CLOSE, CLOSED, SYSTEM);

        // Cancellation. TODO(open-q): cancellation policy. Limited to states before any product
        // has been shipped or work started; extend only once the policy is confirmed.
        for (JobStatus s : List.of(AWAITING_PAYMENT, PAID, CREATOR_INVITED, DECLINED, CREATOR_ACCEPTED)) {
            add(s, CANCEL, CANCELLED, OPS);
        }
    }

    private TransitionTable() {
    }

    private static void add(JobStatus from, JobAction action, JobStatus to, ActorRole... roles) {
        addThen(from, action, to, null, roles);
    }

    private static void addThen(JobStatus from, JobAction action, JobStatus to, JobAction followUp, ActorRole... roles) {
        Set<ActorRole> roleSet = EnumSet.noneOf(ActorRole.class);
        Collections.addAll(roleSet, roles);
        TransitionRule previous = RULES.put(new Key(from, action), new TransitionRule(from, action, to, roleSet, followUp));
        if (previous != null) {
            throw new IllegalStateException("Duplicate transition " + from + " + " + action);
        }
    }

    public static Optional<TransitionRule> find(JobStatus from, JobAction action) {
        return Optional.ofNullable(RULES.get(new Key(from, action)));
    }

    public static List<TransitionRule> all() {
        return List.copyOf(RULES.values());
    }

    /** Every role that may trigger {@code action} from at least one state. */
    public static Set<ActorRole> rolesFor(JobAction action) {
        Set<ActorRole> roles = EnumSet.noneOf(ActorRole.class);
        for (TransitionRule r : RULES.values()) {
            if (r.action() == action) {
                roles.addAll(r.roles());
            }
        }
        return roles;
    }

    public static List<TransitionRule> from(JobStatus state) {
        List<TransitionRule> result = new ArrayList<>();
        for (TransitionRule r : RULES.values()) {
            if (r.from() == state) {
                result.add(r);
            }
        }
        return result;
    }
}
