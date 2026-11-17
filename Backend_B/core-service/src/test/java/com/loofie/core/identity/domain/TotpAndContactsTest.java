package com.loofie.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.loofie.core.identity.domain.Contacts.InvalidContactException;

class TotpAndContactsTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 appendix B, SHA-1 column (8 digits). */
    @Test
    void matchesTheRfc6238TestVectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)), 8)).isEqualTo("94287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)), 8)).isEqualTo("07081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)), 8)).isEqualTo("89005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000)), 8)).isEqualTo("69279037");
    }

    @Test
    void acceptsOneStepOfDriftEitherWayAndNoMore() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.step(now);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step), now)).hasValue(step);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "12345", now)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, null, now)).isEmpty();
    }

    @Test
    void base32AndOtpauthUriAreWhatAuthenticatorAppsRead() {
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.otpauthUri(RFC_SECRET, "ops@loofie.in"))
                .isEqualTo("otpauth://totp/Loofie%3Aops%40loofie.in?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=Loofie&digits=6&period=30");
    }

    @Test
    void indianMobileNumbersInEveryCommonFormatBecomeOneForm() {
        assertThat(Contacts.phone("9876543210")).isEqualTo("+919876543210");
        assertThat(Contacts.phone("+91 98765 43210")).isEqualTo("+919876543210");
        assertThat(Contacts.phone("09876543210")).isEqualTo("+919876543210");
        assertThat(Contacts.phone("919876543210")).isEqualTo("+919876543210");
        assertThat(Contacts.phone("(987) 654-3210")).isEqualTo("+919876543210");
    }

    @Test
    void nonIndianOrInvalidNumbersAreRefused() {
        assertThatThrownBy(() -> Contacts.phone("+1 415 555 0100")).isInstanceOf(InvalidContactException.class)
                .hasMessageContaining("+91");
        assertThatThrownBy(() -> Contacts.phone("5876543210")).isInstanceOf(InvalidContactException.class);
        assertThatThrownBy(() -> Contacts.phone("98765")).isInstanceOf(InvalidContactException.class);
        assertThatThrownBy(() -> Contacts.phone(null)).isInstanceOf(InvalidContactException.class);
        assertThatThrownBy(() -> Contacts.phone("9".repeat(40))).isInstanceOf(InvalidContactException.class);
    }

    @Test
    void emailsAreTrimmedLowerCasedAndChecked() {
        assertThat(Contacts.email("  Riya@Brand.IN ")).isEqualTo("riya@brand.in");
        assertThat(Contacts.email("founder@gmail.com")).isEqualTo("founder@gmail.com"); // any domain (agreed)
        assertThatThrownBy(() -> Contacts.email("riya@brand")).isInstanceOf(InvalidContactException.class);
        assertThatThrownBy(() -> Contacts.email("not an email")).isInstanceOf(InvalidContactException.class);
        assertThatThrownBy(() -> Contacts.email("a".repeat(250) + "@x.in")).isInstanceOf(InvalidContactException.class);
    }

    @Test
    void masksForLogsAndOpsScreens() {
        assertThat(Contacts.mask("+919876543210")).isEqualTo("+91******3210");
        assertThat(Contacts.mask("riya@brand.in")).isEqualTo("r***@brand.in");
        assertThat(Contacts.mask(null)).isNull();
    }
}
