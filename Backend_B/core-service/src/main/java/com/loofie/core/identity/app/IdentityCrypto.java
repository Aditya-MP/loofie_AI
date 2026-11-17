package com.loofie.core.identity.app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Every secret-dependent calculation in one place.
 * <ul>
 *   <li>OTP and recovery codes: HMAC-SHA256 with a server key. A plain hash of a 6-digit code is reversed in
 *       milliseconds, so a database leak alone must not give the codes away.</li>
 *   <li>Session and invite tokens: 256 random bits, stored as SHA-256 (already unguessable, no key needed).</li>
 *   <li>CSRF token: HMAC of the session's token hash, so nothing extra is stored.</li>
 *   <li>Authenticator secrets: AES-256-GCM, because the server needs them back to check codes.</li>
 * </ul>
 * The app does not start without both keys, except with {@code dev-secrets} outside the prod profile.
 */
@Component
public class IdentityCrypto {

    private static final Logger log = LoggerFactory.getLogger(IdentityCrypto.class);
    /** Public on purpose: these only ever protect local and test data. */
    private static final String DEV_PEPPER = "bG9vZmllLWRldi1vbmx5LXBlcHBlci1ub3QtZm9yLXByb2R1Y3Rpb24tdXNl";
    private static final String DEV_TOTP_KEY = "ZGV2LW9ubHktdG90cC1rZXktMzItYnl0ZXMtbG9uZyE=";
    private static final int GCM_IV = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] pepper;
    private final SecretKeySpec totpKey;

    public IdentityCrypto(IdentityProperties props, Environment env) {
        boolean missing = blank(props.otpPepper()) || blank(props.totpKey());
        if (missing && !props.devSecrets()) {
            throw new IllegalStateException("LOOFIE_OTP_PEPPER and LOOFIE_TOTP_KEY must be set (base64, 32+ bytes)");
        }
        if (props.devSecrets() && env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("loofie.identity.dev-secrets must never be on under the prod profile");
        }
        if (missing) {
            log.warn("DEV SECRETS ACTIVE: OTP, session and authenticator data use public dev keys. Local/tests only.");
        }
        this.pepper = decode(blank(props.otpPepper()) ? DEV_PEPPER : props.otpPepper(), "LOOFIE_OTP_PEPPER");
        byte[] key = decode(blank(props.totpKey()) ? DEV_TOTP_KEY : props.totpKey(), "LOOFIE_TOTP_KEY");
        if (key.length != 32) {
            throw new IllegalStateException("LOOFIE_TOTP_KEY must be exactly 32 bytes (base64)");
        }
        this.totpKey = new SecretKeySpec(key, "AES");
    }

    public String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** A 6-digit OTP. */
    public String newOtp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** 10 characters from an alphabet without look-alikes (about 50 bits), shown as xxxxx-xxxxx. */
    public String newRecoveryCode() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                s.append('-');
            }
            s.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return s.toString();
    }

    public byte[] newTotpSecret() {
        byte[] b = new byte[com.loofie.core.identity.domain.Totp.SECRET_BYTES];
        random.nextBytes(b);
        return b;
    }

    public byte[] otpHmac(java.util.UUID challengeId, String code) {
        return hmac("otp|" + challengeId + "|" + code);
    }

    public byte[] recoveryHmac(java.util.UUID userId, String code) {
        return hmac("recovery|" + userId + "|" + code.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public String csrfToken(byte[] sessionTokenHash) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac("csrf|" + HexFormat.of().formatHex(sessionTokenHash)));
    }

    public static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean same(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    public byte[] encryptTotp(byte[] secret) {
        try {
            byte[] iv = new byte[GCM_IV];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, totpKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(secret);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] decryptTotp(byte[] stored) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, totpKey, new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOf(stored, GCM_IV)));
            return c.doFinal(stored, GCM_IV, stored.length - GCM_IV);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Authenticator secret cannot be decrypted (wrong LOOFIE_TOTP_KEY?)", e);
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String b64, String name) {
        try {
            byte[] b = Base64.getDecoder().decode(b64.trim());
            if (b.length < 32) {
                throw new IllegalStateException(name + " must be at least 32 bytes");
            }
            return b;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not valid base64");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
