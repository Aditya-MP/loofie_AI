package com.loofie.core.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;

/**
 * GSTIN shape and check character (E4: format + checksum only; the GST portal is not asked). 15 characters:
 * state code (2 digits), PAN (5 letters, 4 digits, 1 letter), entity number (1-9 or A-Z), "Z", check character.
 * The check character is the GSTN mod-36 checksum over the first 14.
 */
public final class Gstin {

    private static final Pattern SHAPE = Pattern.compile("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private Gstin() {
    }

    /** @return the GSTIN upper-cased and trimmed */
    public static String normalise(String raw) {
        String g = raw == null ? "" : raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!SHAPE.matcher(g).matches()) {
            throw new InvalidRequestException("GSTIN must be 15 characters, like 27AAPFU0939F1ZV");
        }
        int state = Integer.parseInt(g.substring(0, 2));
        if (state < 1 || (state > 38 && state != 97 && state != 99)) {
            throw new InvalidRequestException("GSTIN starts with an unknown state code (" + g.substring(0, 2) + ")");
        }
        if (g.charAt(14) != checkChar(g.substring(0, 14))) {
            throw new InvalidRequestException("This GSTIN has a typing mistake: its last character does not match. "
                    + "Copy it from the GST certificate.");
        }
        return g;
    }

    static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int product = CHARS.indexOf(first14.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }
}
