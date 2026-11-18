package com.loofie.core.draft.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the brand ordered, as far as delivery is checked (F7 D1): the checklist the brand answers, the duration range
 * the video must fit, and how long the brand may run it (D9 retention). A copy kept on the job, so the contract cannot
 * change after the creator started.
 *
 * @param adRightsDays null when the brief did not say; retention then uses the default
 * @param fromBrief    false when no brief was given and the defaults are used (shown to the brand and the creator)
 */
public record Brief(List<ChecklistItem> checklist, int minSeconds, int maxSeconds, Integer adRightsDays,
                    boolean fromBrief) {

    public static final int DEFAULT_MIN_SECONDS = 15;
    public static final int DEFAULT_MAX_SECONDS = 90;
    public static final int MAX_ITEMS = 12;
    public static final int MAX_SECONDS_LIMIT = 600;

    /** One yes / no question for the brand. {@code key} is stable (answers refer to it), {@code label} is shown. */
    public record ChecklistItem(String key, String label) {
        private static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,32}$");

        public ChecklistItem {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new InvalidBriefException("Checklist keys are 1-32 lower-case letters, digits or _");
            }
            label = label == null ? "" : label.strip();
            if (label.isEmpty() || label.length() > 200) {
                throw new InvalidBriefException("Each checklist item needs a label of at most 200 characters");
            }
        }
    }

    public static final class InvalidBriefException extends RuntimeException {
        public InvalidBriefException(String message) {
            super(message);
        }
    }

    public Brief {
        checklist = List.copyOf(checklist);
        if (checklist.isEmpty() || checklist.size() > MAX_ITEMS) {
            throw new InvalidBriefException("The checklist needs 1-" + MAX_ITEMS + " items");
        }
        Set<String> keys = new HashSet<>();
        for (ChecklistItem i : checklist) {
            if (!keys.add(i.key())) {
                throw new InvalidBriefException("Checklist key used twice: " + i.key());
            }
        }
        if (minSeconds < 1 || maxSeconds > MAX_SECONDS_LIMIT || minSeconds > maxSeconds) {
            throw new InvalidBriefException("Duration must be 1-" + MAX_SECONDS_LIMIT + " seconds, minimum not above maximum");
        }
        if (adRightsDays != null && (adRightsDays < 1 || adRightsDays > 3650)) {
            throw new InvalidBriefException("Ad rights must be 1-3650 days");
        }
    }

    /** When no brief was given (D1 not yet wired): the usual UGC checks and 15-90 seconds (D4). */
    public static Brief defaults() {
        return new Brief(List.of(
                new ChecklistItem("hook", "Strong hook in the first 3 seconds"),
                new ChecklistItem("product", "The product is shown clearly"),
                new ChecklistItem("key_points", "Says the key points from the brief"),
                new ChecklistItem("cta", "Ends with the call to action or code"),
                new ChecklistItem("disclosure", "The paid partnership is disclosed")),
                DEFAULT_MIN_SECONDS, DEFAULT_MAX_SECONDS, null, false);
    }

    public boolean hasItem(String key) {
        return checklist.stream().anyMatch(i -> i.key().equals(key));
    }
}
