package com.loofie.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;

class GstinTest {

    /** The GSTN's own published sample, used in its API documentation and widely in invoicing software. */
    @Test
    void acceptsThePublishedSampleAndNormalises() {
        assertThat(Gstin.normalise("27AAPFU0939F1ZV")).isEqualTo("27AAPFU0939F1ZV");
        assertThat(Gstin.normalise(" 27aapfu0939f1zv ")).isEqualTo("27AAPFU0939F1ZV");
        assertThat(Gstin.checkChar("27AAPFU0939F1Z")).isEqualTo('V');
    }

    @Test
    void refusesTyposWrongShapeAndUnknownStates() {
        assertThatThrownBy(() -> Gstin.normalise("27AAPFU0939F1ZW")).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("typing mistake");
        assertThatThrownBy(() -> Gstin.normalise("27AAPFU0939F1Z")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> Gstin.normalise("27AAPF00939F1ZV")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> Gstin.normalise("27AAPFU0939F1YV")).as("14th character is always Z")
                .isInstanceOf(InvalidRequestException.class);
        String state45 = "45AAPFU0939F1Z";
        assertThatThrownBy(() -> Gstin.normalise(state45 + Gstin.checkChar(state45)))
                .hasMessageContaining("unknown state code");
        assertThatThrownBy(() -> Gstin.normalise(null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void everySingleCharacterChangeIsCaught() {
        String good = "27AAPFU0939F1ZV";
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int i = 2; i < 14; i++) {
            for (char c : chars.toCharArray()) {
                if (c == good.charAt(i)) {
                    continue;
                }
                String typo = good.substring(0, i) + c + good.substring(i + 1);
                boolean accepted;
                try {
                    Gstin.normalise(typo);
                    accepted = true;
                } catch (InvalidRequestException e) {
                    accepted = false;
                }
                assertThat(accepted).as(typo).isFalse();
            }
        }
    }
}
