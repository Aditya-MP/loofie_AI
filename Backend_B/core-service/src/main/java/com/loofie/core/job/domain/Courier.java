package com.loofie.core.job.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A courier brands can pick when they ship (ops-editable table, DECISIONS.md section 5).
 *
 * @param trackingUrlPattern {@code https://...{tracking}...}, or null until ops adds it
 */
public record Courier(String code, String name, Kind kind, String trackingUrlPattern, boolean active, int sortOrder) {

    public enum Kind {
        /** A courier with tracking numbers: the number is required. */
        TRACKED,
        /** Porter, Rapido, the brand's own staff: no tracking number, a handover photo instead. */
        LOCAL,
        /** Not in the list: the courier's name and a tracking link are both required. */
        OTHER
    }

    public static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{2,32}$");
    public static final String PLACEHOLDER = "{tracking}";

    /** One-click tracking for ops; null when there is no pattern or no number. */
    public String trackingLink(String trackingNumber) {
        if (trackingUrlPattern == null || trackingNumber == null) {
            return null;
        }
        return trackingUrlPattern.replace(PLACEHOLDER, URLEncoder.encode(trackingNumber, StandardCharsets.UTF_8));
    }

    /** Only https links ops can open safely, with the placeholder where the number goes. */
    public static boolean isValidPattern(String pattern) {
        return pattern == null || (pattern.startsWith("https://") && pattern.contains(PLACEHOLDER)
                && pattern.length() <= 300 && !pattern.contains(" "));
    }

    /** How tracking numbers are compared: upper case, without spaces or dashes ("dl 123-45" = "DL12345"). */
    public static String normaliseTrackingNumber(String number) {
        return number == null ? null : number.replaceAll("[\\s-]", "").toUpperCase();
    }
}
