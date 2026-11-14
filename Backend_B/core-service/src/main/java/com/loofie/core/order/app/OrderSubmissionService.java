package com.loofie.core.order.app;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.common.Ids;
import com.loofie.core.job.app.JobIntakeService;
import com.loofie.core.job.app.JobIntakeService.NewJob;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.order.domain.Order;
import com.loofie.core.order.infra.OrderRepository;

/**
 * Sprint 1-2 stub of order submission: takes picks directly instead of reading the frozen brief
 * and shortlist from Backend A (contracts 3 and 4, Sprint 3-4). Applies the spec's money guards.
 * TODO(F4): MSA acceptance check once the contracts module exists.
 */
@Service
public class OrderSubmissionService {

    /** Minimum order value: Rs 15,000 in creator fees. TODO(open-q): does this apply to rebooks? */
    public static final long MIN_ORDER_FEES_PAISE = 15_000_00L;
    /** NEW-level creators are capped at Rs 5,000 per job. */
    public static final long NEW_CREATOR_MAX_FEE_PAISE = 5_000_00L;

    public record Pick(boolean digitalOnly, long creatorFeePaise, boolean newCreator) {
    }

    public record SubmittedOrder(UUID orderId, List<UUID> jobIds) {
    }

    private final OrderRepository orders;
    private final JobIntakeService intake;
    private final Clock clock;

    public OrderSubmissionService(OrderRepository orders, JobIntakeService intake, Clock clock) {
        this.orders = orders;
        this.intake = intake;
        this.clock = clock;
    }

    @Transactional
    public SubmittedOrder submit(UUID brandOrgId, Actor actor, List<Pick> picks) {
        if (picks.isEmpty()) {
            throw new TransitionGuardException("An order needs at least one creator");
        }
        long total = 0;
        for (Pick p : picks) {
            if (p.creatorFeePaise() <= 0) {
                throw new TransitionGuardException("Creator fee must be positive");
            }
            if (p.newCreator() && p.creatorFeePaise() > NEW_CREATOR_MAX_FEE_PAISE) {
                throw new TransitionGuardException("NEW creators are capped at Rs 5,000 per job");
            }
            total = Math.addExact(total, p.creatorFeePaise());
        }
        if (total < MIN_ORDER_FEES_PAISE) {
            throw new TransitionGuardException("Order total must be at least Rs 15,000 in creator fees");
        }

        Order order = Order.create(Ids.newId(), brandOrgId, clock.instant());
        orders.saveAndFlush(order);
        List<NewJob> jobs = picks.stream().map(p -> new NewJob(p.digitalOnly(), p.creatorFeePaise())).toList();
        List<UUID> jobIds = intake.createJobs(order.getId(), brandOrgId, actor, jobs, Ids.newId());
        return new SubmittedOrder(order.getId(), jobIds);
    }
}
