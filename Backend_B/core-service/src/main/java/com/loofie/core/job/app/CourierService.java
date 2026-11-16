package com.loofie.core.job.app;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.Courier;
import com.loofie.core.job.domain.JobExceptions.TransitionGuardException;
import com.loofie.core.job.infra.CourierRepository;

/**
 * The courier list (DECISIONS.md section 5): brands pick from the active ones when they ship; ops edits it.
 * There is always exactly one LOCAL and one OTHER row: the shipping rules depend on them.
 */
@Service
public class CourierService {

    /** What brands see in the "Shipped" form. */
    public record CourierOption(String code, String name, Courier.Kind kind) {
    }

    private final CourierRepository couriers;
    private final Clock clock;

    public CourierService(CourierRepository couriers, Clock clock) {
        this.couriers = couriers;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CourierOption> active() {
        return couriers.all().stream().filter(Courier::active)
                .map(c -> new CourierOption(c.code(), c.name(), c.kind())).toList();
    }

    @Transactional(readOnly = true)
    public List<Courier> all(Actor actor) {
        requireOps(actor);
        return couriers.all();
    }

    /** Adds or edits a courier. Returns true when it was new. */
    @Transactional
    public boolean upsert(Actor actor, Courier c) {
        requireOps(actor);
        if (c.code() == null || !Courier.CODE.matcher(c.code()).matches()) {
            throw new TransitionGuardException("Courier code: 2-32 capital letters, digits or _ (e.g. BLUE_DART)");
        }
        if (c.name() == null || c.name().isBlank() || c.name().length() > 64) {
            throw new TransitionGuardException("Courier name: 1-64 characters");
        }
        if (!Courier.isValidPattern(c.trackingUrlPattern())) {
            throw new TransitionGuardException("Tracking link pattern: an https:// address containing {tracking} "
                    + "where the number goes, at most 300 characters");
        }
        if (c.kind() != Courier.Kind.TRACKED && !c.code().equals(c.kind().name())) {
            throw new TransitionGuardException("LOCAL and OTHER are fixed rows; add new couriers as TRACKED");
        }
        if (c.kind() == Courier.Kind.TRACKED && (c.code().equals("LOCAL") || c.code().equals("OTHER"))) {
            throw new TransitionGuardException(c.code() + " must keep its kind " + c.code());
        }
        if ((c.code().equals("LOCAL") || c.code().equals("OTHER")) && !c.active()) {
            throw new TransitionGuardException(c.code() + " cannot be switched off: shipping depends on it");
        }
        return couriers.upsert(new Courier(c.code(), c.name().trim(), c.kind(), c.trackingUrlPattern(), c.active(),
                c.sortOrder()), actor.userId(), clock.instant());
    }

    private static void requireOps(Actor actor) {
        actor.requireRole(ActorRole.OPS, "The courier list is managed by OPS only");
    }
}
