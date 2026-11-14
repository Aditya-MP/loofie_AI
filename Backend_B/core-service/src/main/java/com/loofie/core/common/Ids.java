package com.loofie.core.common;

import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7 ids (RFC 9562): 48-bit Unix milliseconds, then random bits. Time-ordered, so index-friendly. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    static UUID newId(long epochMillis) {
        byte[] r = new byte[10];
        RANDOM.nextBytes(r);
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16   // unix_ts_ms (48 bits)
                | 0x7000L                                     // version 7
                | ((r[0] & 0x0FL) << 8) | (r[1] & 0xFFL);     // rand_a (12 bits)
        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (r[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10 + rand_b (62 bits)
        return new UUID(msb, lsb);
    }
}
