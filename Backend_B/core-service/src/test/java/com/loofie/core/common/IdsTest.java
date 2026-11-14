package com.loofie.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void isVersion7WithRfcVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampSoIdsSortByTime() {
        UUID earlier = Ids.newId(1_700_000_000_000L);
        UUID later = Ids.newId(1_700_000_000_001L);
        assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(1_700_000_000_000L);
        assertThat(Long.compareUnsigned(earlier.getMostSignificantBits(), later.getMostSignificantBits())).isNegative();
    }

    @Test
    void doesNotCollide() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(Ids.newId())).isTrue();
        }
    }
}
