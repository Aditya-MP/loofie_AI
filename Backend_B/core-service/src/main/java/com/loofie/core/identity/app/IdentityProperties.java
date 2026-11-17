package com.loofie.core.identity.app;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code loofie.identity.*}. Limits are the ones agreed on 2026-11-17 (docs/features/f1-identity-round-a.md).
 *
 * @param sender              {@code none} (default: sending fails closed with a 503) or {@code log} (local / tests:
 *                            the message is written to the log instead of sent). Real providers come later.
 * @param otpPepper           base64 key (32+ bytes) for OTP, recovery-code and CSRF HMACs. Env LOOFIE_OTP_PEPPER.
 * @param totpKey             base64 AES-256 key for authenticator secrets. Env LOOFIE_TOTP_KEY.
 * @param devSecrets          use fixed, public dev keys when the two above are empty. Local and tests only; refused
 *                            under the prod profile.
 * @param noticeVersion       privacy notice / terms version recorded with each consent
 * @param cookieSecure        Secure cookie with the __Host- prefix (true everywhere except plain-http local runs)
 * @param allowedOrigins      web app origins allowed to call with cookies (CORS), e.g. https://app.loofie.in
 * @param appBaseUrl          where invite links point
 * @param bootstrapFounderEmail creates the first FOUNDER account at start-up when no founder exists yet
 */
@ConfigurationProperties("loofie.identity")
public record IdentityProperties(String sender, String otpPepper, String totpKey, boolean devSecrets,
                                 String noticeVersion, Boolean cookieSecure, List<String> allowedOrigins,
                                 String appBaseUrl, String bootstrapFounderEmail,
                                 Duration otpTtl, int otpMaxAttempts, int otpPerDestination15m,
                                 int otpPerDestinationDay, int otpPerIp15m, int verifyPerIp15m, int mfaPerUser15m,
                                 int smsDailyCap, Duration userSessionIdle, Duration userSessionAbsolute,
                                 Duration staffSession, Duration mfaSessionTtl, Duration inviteTtl) {

    public IdentityProperties {
        sender = sender == null || sender.isBlank() ? "none" : sender.trim();
        noticeVersion = noticeVersion == null || noticeVersion.isBlank() ? "unversioned" : noticeVersion.trim();
        cookieSecure = cookieSecure == null || cookieSecure;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        appBaseUrl = appBaseUrl == null || appBaseUrl.isBlank() ? "https://app.loofie.in" : appBaseUrl.trim();
        otpTtl = otpTtl == null ? Duration.ofMinutes(5) : otpTtl;
        otpMaxAttempts = otpMaxAttempts <= 0 ? 5 : otpMaxAttempts;
        otpPerDestination15m = otpPerDestination15m <= 0 ? 3 : otpPerDestination15m;
        otpPerDestinationDay = otpPerDestinationDay <= 0 ? 10 : otpPerDestinationDay;
        otpPerIp15m = otpPerIp15m <= 0 ? 100 : otpPerIp15m;
        verifyPerIp15m = verifyPerIp15m <= 0 ? 200 : verifyPerIp15m;
        mfaPerUser15m = mfaPerUser15m <= 0 ? 5 : mfaPerUser15m;
        smsDailyCap = smsDailyCap <= 0 ? 5000 : smsDailyCap;
        userSessionIdle = userSessionIdle == null ? Duration.ofDays(30) : userSessionIdle;
        userSessionAbsolute = userSessionAbsolute == null ? Duration.ofDays(90) : userSessionAbsolute;
        staffSession = staffSession == null ? Duration.ofHours(12) : staffSession;
        mfaSessionTtl = mfaSessionTtl == null ? Duration.ofMinutes(10) : mfaSessionTtl;
        inviteTtl = inviteTtl == null ? Duration.ofDays(7) : inviteTtl;
    }

    public String cookieName() {
        return cookieSecure ? "__Host-loofie_session" : "loofie_session";
    }
}
