package com.loofie.core.identity.domain;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticator-app codes (RFC 6238: HMAC-SHA1, 30 s steps, 6 digits), as Google Authenticator, Authy and 1Password
 * produce them. One step of clock drift either way is accepted.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    public static final int SECRET_BYTES = 20;
    private static final int DRIFT_STEPS = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** RFC 4226 truncation; {@code digits} is 6 for the app, 8 for the RFC test vectors. */
    static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8)
                    | (h[off + 3] & 0xff);
            return String.format("%0" + digits + "d", bin % (int) Math.pow(10, digits));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(byte[] secret, long step) {
        return code(secret, step, DIGITS);
    }

    /** @return the matching step, or empty. The caller must refuse a step not after the last accepted one. */
    public static OptionalLong match(byte[] secret, String code, Instant at) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long now = step(at);
        for (long s = now - DRIFT_STEPS; s <= now + DRIFT_STEPS; s++) {
            if (MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** What the authenticator app scans (the frontend draws it as a QR code). */
    public static String otpauthUri(byte[] secret, String accountLabel) {
        String label = URLEncoder.encode("Loofie:" + accountLabel, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=Loofie&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }
}
