package com.loofie.core.calendar.app;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.domain.WorkingDays;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.job.domain.WorkingCalendar;

/**
 * The job engine's {@link WorkingCalendar}: loads the holidays that matter, then applies the pure
 * working-day rules in {@link WorkingDays}.
 */
@Service
public class HolidayCalendar implements WorkingCalendar {

    private final HolidayRepository holidays;

    public HolidayCalendar(HolidayRepository holidays) {
        this.holidays = holidays;
    }

    @Override
    public Instant endOfWorkingDays(Instant from, int days, String stateCode) {
        LocalDate start = WorkingDays.startDate(from);
        var closed = holidays.closedDates(start, start.plusDays(WorkingDays.LOOKAHEAD_DAYS),
                Holiday.normaliseState(stateCode));
        return WorkingDays.endOf(from, days, closed);
    }
}
