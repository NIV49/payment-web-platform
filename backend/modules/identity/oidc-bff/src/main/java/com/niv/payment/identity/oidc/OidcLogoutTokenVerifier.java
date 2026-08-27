package com.niv.payment.identity.oidc;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class OidcLogoutTokenVerifier {
    static final String BACKCHANNEL_EVENT = "http://schemas.openid.net/event/backchannel-logout";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final OidcClientSettings settings;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final Duration maximumAge;

    public OidcLogoutTokenVerifier(OidcClientSettings settings, JwtDecoder decoder,
                                   Clock clock, Duration maximumAge) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = requirePositive(maximumAge);
    }

    public LogoutIdentity verify(String signedLogout) {
        if (signedLogout == null || signedLogout.isBlank() || signedLogout.length() > 16_384) {
            throw new BackChannelLogoutRejectedException(Reason.TOKEN_INVALID);
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(signedLogout);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BackChannelLogoutRejectedException(Reason.DECODER_REJECTED, exception);
        }
        String issuer = required(
            jwt.getIssuer() == null ? null : jwt.getIssuer().toString(), Reason.IDENTITY_INVALID);
        if (!settings.issuer().toString().equals(issuer)
            || !jwt.getAudience().contains(settings.clientId())) {
            throw new BackChannelLogoutRejectedException(Reason.IDENTITY_INVALID);
        }
        Instant now = clock.instant();
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null || issuedAt.isAfter(now.plus(CLOCK_SKEW))
            || issuedAt.isBefore(now.minus(maximumAge).minus(CLOCK_SKEW))) {
            throw new BackChannelLogoutRejectedException(Reason.TIME_INVALID);
        }
        String eventId = required(jwt.getId(), Reason.EVENT_INVALID);
        Object eventsClaim = jwt.getClaim("events");
        if (!(eventsClaim instanceof Map<?, ?> events)
            || !(events.get(BACKCHANNEL_EVENT) instanceof Map<?, ?>)
            || jwt.hasClaim("nonce")) {
            throw new BackChannelLogoutRejectedException(Reason.EVENT_INVALID);
        }
        Object type = jwt.getHeaders().get("typ");
        if (type != null && !"logout+jwt".equals(type)) {
            throw new BackChannelLogoutRejectedException(Reason.TYPE_INVALID);
        }
        String subject = optional(jwt.getSubject(), Reason.SESSION_IDENTITY_INVALID);
        String sessionId = optional(jwt.getClaimAsString("sid"), Reason.SESSION_IDENTITY_INVALID);
        if (subject == null && sessionId == null) {
            throw new BackChannelLogoutRejectedException(Reason.SESSION_IDENTITY_INVALID);
        }
        return new LogoutIdentity(issuer, subject, sessionId, eventId);
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Logout event maximum age must be positive");
        }
        return value;
    }

    private static String required(String value, Reason reason) {
        String result = optional(value, reason);
        if (result == null) {
            throw new BackChannelLogoutRejectedException(reason);
        }
        return result;
    }

    private static String optional(String value, Reason reason) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 512) {
            throw new BackChannelLogoutRejectedException(reason);
        }
        return value;
    }

    public record LogoutIdentity(String issuer, String subject, String sessionId, String eventId) { }

    enum Reason {
        DECODER_REJECTED,
        EVENT_INVALID,
        FORM_FIELDS_INVALID,
        FORM_TOKEN_INVALID,
        FORM_VALUES_INVALID,
        IDENTITY_INVALID,
        SESSION_IDENTITY_INVALID,
        TIME_INVALID,
        TOKEN_INVALID,
        TYPE_INVALID
    }

    public static final class BackChannelLogoutRejectedException extends RuntimeException {
        private final Reason reason;

        public BackChannelLogoutRejectedException() {
            this(Reason.FORM_FIELDS_INVALID);
        }

        BackChannelLogoutRejectedException(Reason reason) {
            super("OIDC back-channel logout was rejected");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        BackChannelLogoutRejectedException(Reason reason, Throwable cause) {
            super("OIDC back-channel logout was rejected", cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        Reason reason() {
            return reason;
        }
    }
}
