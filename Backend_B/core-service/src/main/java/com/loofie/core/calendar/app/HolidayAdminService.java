package com.loofie.core.calendar.app;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;

/**
 * F10: ops edits the holiday table. Only OPS may read or change it; the check lives here, not in the
 * controller, so every entry point gets it. Changes apply to clocks started afterwards; running
 * deadlines are not recalculated.
 */
@Service
public class HolidayAdminService {

    private final HolidayRepository holidays;

    public HolidayAdminService(HolidayRepository holidays) {
        this.holidays = holidays;
    }

    @Transactional(readOnly = true)
    public List<Holiday> list(Actor actor, int year) {
        actor.requireRole(ActorRole.OPS, "Holidays are managed by OPS only");
        return holidays.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** Returns true when the holiday was new, false when an existing one was renamed. */
    @Transactional
    public boolean upsert(Actor actor, LocalDate date, String stateCode, String name) {
        actor.requireRole(ActorRole.OPS, "Holidays are managed by OPS only");
        return holidays.upsert(date, Holiday.normaliseState(stateCode), name.trim(), actor.userId());
    }

    @Transactional
    public boolean delete(Actor actor, LocalDate date, String stateCode) {
        actor.requireRole(ActorRole.OPS, "Holidays are managed by OPS only");
        return holidays.delete(date, Holiday.normaliseState(stateCode));
    }
}
