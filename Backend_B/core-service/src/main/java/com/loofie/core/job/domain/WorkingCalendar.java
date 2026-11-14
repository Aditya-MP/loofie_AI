package com.loofie.core.job.domain;

import java.time.Instant;

/**
 * Brand-side clocks count working days: Monday to Saturday, minus national holidays and, when the
 * brand's pickup state is known, that state's holidays. Creator-side clocks stay in calendar hours.
 */
public interface WorkingCalendar {

    /**
     * The end (midnight, India time) of the {@code days}-th working day after the day {@code from} falls on.
     * Example: accepted on a Monday, 3 working days → due at the end of Thursday.
     *
     * @param stateCode the brand's pickup state, or null when unknown (national holidays only)
     */
    Instant endOfWorkingDays(Instant from, int days, String stateCode);
}
