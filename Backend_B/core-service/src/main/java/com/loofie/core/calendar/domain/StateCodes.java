package com.loofie.core.calendar.domain;

import java.util.Map;
import java.util.Set;

/**
 * Indian state and union territory codes, ISO 3166-2:IN as of the 2023-11-23 change (28 states, 8 UTs; checked
 * against the published list on 2026-11-16). A holiday for any other code would be stored and never match a brand,
 * so it is refused. Codes ISO retired in 2023 are still accepted and stored as the current code.
 */
public final class StateCodes {

    public static final Set<String> CODES = Set.of(
            // states
            "AP", "AR", "AS", "BR", "CG", "GA", "GJ", "HR", "HP", "JH", "KA", "KL", "MP", "MH", "MN", "ML", "MZ", "NL",
            "OD", "PB", "RJ", "SK", "TN", "TS", "TR", "UP", "UK", "WB",
            // union territories
            "AN", "CH", "DH", "DL", "JK", "LA", "LD", "PY");

    /** Retired by ISO in 2023: Odisha, Chhattisgarh, Telangana, Uttarakhand. */
    static final Map<String, String> RENAMED = Map.of("OR", "OD", "CT", "CG", "TG", "TS", "UT", "UK");

    private StateCodes() {
    }

    /**
     * Upper-case current code, {@link Holiday#NATIONAL} for null or blank. Accepts "wb", "IN-WB" and retired codes.
     *
     * @throws InvalidStateCodeException for anything else
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return Holiday.NATIONAL;
        }
        String code = raw.trim().toUpperCase();
        if (code.startsWith("IN-")) {
            code = code.substring(3);
        }
        code = RENAMED.getOrDefault(code, code);
        if (!CODES.contains(code)) {
            throw new InvalidStateCodeException(raw);
        }
        return code;
    }

    public static final class InvalidStateCodeException extends RuntimeException {
        public InvalidStateCodeException(String raw) {
            super("Unknown state code '" + raw + "': use an Indian state or union territory code such as WB, KA, MH");
        }
    }
}
