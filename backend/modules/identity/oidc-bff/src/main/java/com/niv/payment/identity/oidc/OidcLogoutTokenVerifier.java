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
            throw new BackChannelLogoutRejectedException();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(signedLogout);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BackChannelLogoutRejectedException(exception);
        }
        String issuer = required(jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
        if (!settings.issuer().toString().equals(issuer)
            || !jwt.getAudience().contains(settings.clientId())) {
            throw new BackChannelLogoutRejectedException();
        }
        Instant now = clock.instant();
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null || issuedAt.isAfter(now.plus(CLOCK_SKEW))
            || issuedAt.isBefore(now.minus(maximumAge).minus(CLOCK_SKEW))) {
            throw new BackChannelLogoutRejectedException();
        }
        String eventId = required(jwt.getId());
        Object eventsClaim = jwt.getClaim("events");
        if (!(eventsClaim instanceof Map<?, ?> events)
            || !(events.get(BACKCHANNEL_EVENT) instanceof Map<?, ?>)
            || jwt.hasClaim("nonce")) {
            throw new BackChannelLogoutRejectedException();
        }
        Object type = jwt.getHeaders().get("typ");
        if (type != null && !"logout+jwt".equals(type)) {
            throw new BackChannelLogoutRejectedException();
        }
        String subject = optional(jwt.getSubject());
        String sessionId = optional(jwt.getClaimAsString("sid"));
        if (subject == null && sessionId == null) {
            throw new BackChannelLogoutRejectedException();
        }
        return new LogoutIdentity(issuer, subject, sessionId, eventId);
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Logout event maximum age must be positive");
        }
        return value;
    }

    private static String required(String value) {
        String result = optional(value);
        if (result == null) {
            throw new BackChannelLogoutRejectedException();
        }
        return result;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 512) {
            throw new BackChannelLogoutRejectedException();
        }
        return value;
    }

    public record LogoutIdentity(String issuer, String subject, String sessionId, String eventId) { }

    public static final class BackChannelLogoutRejectedException extends RuntimeException {
        public BackChannelLogoutRejectedException() {
            super("OIDC back-channel logout was rejected");
        }

        BackChannelLogoutRejectedException(Throwable cause) {
            super("OIDC back-channel logout was rejected", cause);
        }
    }
}
