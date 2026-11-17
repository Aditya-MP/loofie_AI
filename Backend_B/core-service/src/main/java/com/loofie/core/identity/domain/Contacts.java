package com.loofie.core.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Puts phones and emails into the one stored form, so "098765 43210" and "+91 9876543210" are the same person. */
public final class Contacts {

    /** Indian mobile numbers start with 6-9 and have 10 digits. */
    private static final Pattern INDIAN_MOBILE = Pattern.compile("[6-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+");
    public static final int MAX_EMAIL = 254;

    private Contacts() {
    }

    /** @return +91XXXXXXXXXX */
    public static String phone(String raw) {
        if (raw == null || raw.length() > 32) {
            throw new InvalidContactException("Enter a 10-digit Indian mobile number");
        }
        String digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+")) {
            if (!digits.startsWith("+91")) {
                throw new InvalidContactException("Only Indian (+91) mobile numbers are supported");
            }
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!INDIAN_MOBILE.matcher(digits).matches()) {
            throw new InvalidContactException("Enter a 10-digit Indian mobile number");
        }
        return "+91" + digits;
    }

    public static String email(String raw) {
        String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (e.length() > MAX_EMAIL || !EMAIL.matcher(e).matches()) {
            throw new InvalidContactException("Enter a valid email address");
        }
        return e;
    }

    public static String normalise(Channel channel, String raw) {
        return channel == Channel.PHONE ? phone(raw) : email(raw);
    }

    /** For logs and ops screens: +91******3210, r***@brand.in. */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("+")) {
            return value.substring(0, 3) + "******" + value.substring(value.length() - 4);
        }
        int at = value.indexOf('@');
        return at <= 1 ? "***" + value.substring(Math.max(at, 0)) : value.charAt(0) + "***" + value.substring(at);
    }

    public static class InvalidContactException extends RuntimeException {
        public InvalidContactException(String message) {
            super(message);
        }
    }
}
