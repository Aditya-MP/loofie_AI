package com.loofie.core.calendar.domain;

import java.time.LocalDate;

/**
 * A day brand-side clocks skip. {@code stateCode} is {@link #NATIONAL} (empty) for a national holiday,
 * otherwise an upper-case state code such as "WB": it then applies only to brands picking up there.
 */
public record Holiday(LocalDate date, String stateCode, String name) {

    public static final String NATIONAL = "";

    /** State codes are stored upper-case; null or blank means national. */
    public static String normaliseState(String stateCode) {
        return stateCode == null || stateCode.isBlank() ? NATIONAL : stateCode.trim().toUpperCase();
    }
}
