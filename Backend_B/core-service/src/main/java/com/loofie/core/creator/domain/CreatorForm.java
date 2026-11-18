package com.loofie.core.creator.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.loofie.core.calendar.domain.StateCodes;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;

/**
 * What the creator types, cleaned and checked. Every field may be empty while she is still filling the form (her
 * progress is saved); {@link #missing} lists what is still needed before the passport is complete.
 */
public record CreatorForm(String displayName, String city, String state, List<String> languages,
                          List<String> categories, List<CreatorProfile.Format> formats, Integer ratePerReelRupees,
                          Boolean partnershipAds, Integer turnaroundDays, String bio, List<String> sampleLinks,
                          String instagramHandle, Long followers, Long avgViews) {

    public static final int MIN_RATE = 100;
    public static final int MAX_RATE = 500_000;
    public static final int MAX_LANGUAGES = 3;
    public static final int MAX_CATEGORIES = 3;
    public static final int MAX_LINKS = 5;
    public static final int MAX_BIO = 300;
    public static final long MAX_FOLLOWERS = 100_000_000L;

    private static final Pattern HANDLE = Pattern.compile("[a-z0-9._]{1,30}");
    private static final Set<String> LINK_HOSTS = Set.of("instagram.com", "www.instagram.com", "youtube.com",
            "www.youtube.com", "youtu.be", "m.youtube.com");

    /**
     * Cleans and checks raw input against the configured lists.
     *
     * @throws InvalidRequestException (422) with a message she can act on
     */
    public static CreatorForm clean(CreatorForm raw, Set<String> knownCategories, Set<String> knownLanguages) {
        String name = trimToNull(raw.displayName());
        if (name != null && (name.length() < 2 || name.length() > 80)) {
            throw new InvalidRequestException("Name must be 2 to 80 characters");
        }
        String city = trimToNull(raw.city());
        if (city != null && (city.length() < 2 || city.length() > 80)) {
            throw new InvalidRequestException("City must be 2 to 80 characters");
        }
        String state = raw.state() == null || raw.state().isBlank() ? null : StateCodes.normalise(raw.state());
        List<String> languages = pick(raw.languages(), knownLanguages, MAX_LANGUAGES, "language");
        List<String> categories = pick(raw.categories(), knownCategories, MAX_CATEGORIES, "category");
        List<CreatorProfile.Format> formats = raw.formats() == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(raw.formats().stream().filter(f -> f != null).toList()));
        Integer rate = raw.ratePerReelRupees();
        if (rate != null && (rate < MIN_RATE || rate > MAX_RATE)) {
            throw new InvalidRequestException("Rate per reel must be between ₹" + MIN_RATE + " and ₹5,00,000");
        }
        Integer days = raw.turnaroundDays();
        if (days != null && (days < 1 || days > 30)) {
            throw new InvalidRequestException("Turnaround must be 1 to 30 days");
        }
        String bio = trimToNull(raw.bio());
        if (bio != null && bio.length() > MAX_BIO) {
            throw new InvalidRequestException("Bio must be at most " + MAX_BIO + " characters");
        }
        List<String> links = links(raw.sampleLinks());
        String handle = handle(raw.instagramHandle());
        Long followers = number(raw.followers(), MAX_FOLLOWERS, "Followers");
        Long views = number(raw.avgViews(), 10_000_000_000L, "Average views");
        return new CreatorForm(name, city, state, languages, categories, formats, rate, raw.partnershipAds(), days, bio,
                links, handle, followers, views);
    }

    /** Fields still needed, by name, in form order (the app highlights them). Numbers are checked separately. */
    public List<String> missingFields() {
        List<String> m = new ArrayList<>();
        if (displayName == null) {
            m.add("displayName");
        }
        if (city == null) {
            m.add("city");
        }
        if (state == null) {
            m.add("state");
        }
        if (languages.isEmpty()) {
            m.add("languages");
        }
        if (categories.isEmpty()) {
            m.add("categories");
        }
        if (formats.isEmpty()) {
            m.add("formats");
        }
        if (ratePerReelRupees == null) {
            m.add("ratePerReelRupees");
        }
        if (partnershipAds == null) {
            m.add("partnershipAds");
        }
        if (turnaroundDays == null) {
            m.add("turnaroundDays");
        }
        if (sampleLinks.isEmpty()) {
            m.add("sampleLinks");
        }
        if (instagramHandle == null) {
            m.add("instagramHandle");
        }
        return m;
    }

    public static String handle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String h = raw.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("@")) {
            h = h.substring(1);
        }
        if (!HANDLE.matcher(h).matches()) {
            throw new InvalidRequestException("Instagram handle: letters, numbers, '.' and '_' only, up to 30");
        }
        return h;
    }

    private static List<String> pick(List<String> raw, Set<String> known, int max, String what) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String v = known.stream().filter(k -> k.equalsIgnoreCase(s.trim())).findFirst().orElseThrow(
                    () -> new InvalidRequestException("Unknown " + what + ": " + s.trim()));
            out.add(v);
        }
        if (out.size() > max) {
            throw new InvalidRequestException("Pick at most " + max + " (" + what + ")");
        }
        return List.copyOf(out);
    }

    /** Only https links to Instagram or YouTube, shown to brands as plain links (never embedded). */
    private static List<String> links(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String link = s.trim();
            URI u;
            try {
                u = URI.create(link);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Not a valid link: " + link);
            }
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || !LINK_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT)) || u.getUserInfo() != null
                    || link.length() > 300) {
                throw new InvalidRequestException("Sample links must be https links to Instagram or YouTube");
            }
            out.add(link);
        }
        if (out.size() > MAX_LINKS) {
            throw new InvalidRequestException("At most " + MAX_LINKS + " sample links");
        }
        return List.copyOf(out);
    }

    private static Long number(Long v, long max, String what) {
        if (v != null && (v < 0 || v > max)) {
            throw new InvalidRequestException(what + " must be between 0 and " + max);
        }
        return v;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
