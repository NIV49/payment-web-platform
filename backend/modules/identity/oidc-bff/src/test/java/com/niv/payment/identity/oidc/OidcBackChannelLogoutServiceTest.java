package com.niv.payment.identity.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcBackChannelLogoutServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T04:00:00Z");
    private static final OidcClientSettings SETTINGS = new OidcClientSettings(
        URI.create("https://idp.example.test/realms/PLATFORM"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/auth"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/token"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/certs"),
        URI.create("https://idp.example.test/realms/PLATFORM/protocol/openid-connect/logout"),
        "platform-admin-api", "client-secret",
        URI.create("https://api.ops.example.com/api/auth/oidc/callback"),
        URI.create("https://ops.example.com/login"), "2");

    @Test
    void verifiesRequiredBackChannelClaimsAndRejectsNonceOrStaleEvents() {
        Jwt valid = logoutJwt("event-1", "session-1", NOW.minusSeconds(30), Map.of());
        OidcLogoutTokenVerifier verifier = verifier(valid);

        assertThat(verifier.verify("signed-logout").sessionId()).isEqualTo("session-1");

        assertRejected(logoutJwt("event-2", "session-1", NOW.minusSeconds(30),
            Map.of("nonce", "not-allowed")));
        assertRejected(logoutJwt("event-3", "session-1", NOW.minusSeconds(600), Map.of()));
        assertRejected(logoutJwt("event-4", null, NOW.minusSeconds(30),
            Map.of("events", Map.of())));
        assertRejected(Jwt.withTokenValue("wrong-audience")
            .header("alg", "RS256").issuer(SETTINGS.issuer().toString()).subject("subject-1")
            .audience(List.of("other-client")).issuedAt(NOW.minusSeconds(30)).claim("jti", "event-5")
            .claim("events", Map.of(OidcLogoutTokenVerifier.BACKCHANNEL_EVENT, Map.of())).build());
    }

    @Test
    void sidTakesPrecedenceAndACompletedEventIsIdempotent() {
        FakeIndex index = new FakeIndex(Set.of(10L, 20L), Set.of(30L));
        List<Long> revoked = new ArrayList<>();
        var service = new OidcBackChannelLogoutService(
            verifier(logoutJwt("event-1", "session-1", NOW.minusSeconds(30), Map.of())),
            index, revoked::add);

        service.logout("signed-logout");
        service.logout("signed-logout");

        assertThat(revoked).containsExactlyInAnyOrder(10L, 20L);
        assertThat(index.sessionLookups).isEqualTo(1);
        assertThat(index.subjectLookups).isZero();
        assertThat(index.completed).containsExactly("event-1");
    }

    @Test
    void revocationFailureReleasesTheEventForRetry() {
        FakeIndex index = new FakeIndex(Set.of(10L), Set.of());
        var service = new OidcBackChannelLogoutService(
            verifier(logoutJwt("event-1", "session-1", NOW.minusSeconds(30), Map.of())),
            index, membershipId -> { throw new IllegalStateException("session store unavailable"); });

        assertThatThrownBy(() -> service.logout("signed-logout"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(index.released).containsExactly("event-1");
        assertThat(index.completed).isEmpty();
    }

    private static void assertRejected(Jwt jwt) {
        assertThatThrownBy(() -> verifier(jwt).verify("signed-logout"))
            .isInstanceOf(OidcLogoutTokenVerifier.BackChannelLogoutRejectedException.class);
    }

    private static OidcLogoutTokenVerifier verifier(Jwt jwt) {
        return new OidcLogoutTokenVerifier(SETTINGS, signed -> jwt,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
    }

    private static Jwt logoutJwt(String eventId, String sessionId, Instant issuedAt,
                                 Map<String, Object> replacements) {
        Map<String, Object> events = Map.of(OidcLogoutTokenVerifier.BACKCHANNEL_EVENT, Map.of());
        var builder = Jwt.withTokenValue("signed-logout")
            .header("alg", "RS256").header("typ", "logout+jwt")
            .issuer(SETTINGS.issuer().toString()).subject("subject-1")
            .audience(List.of(SETTINGS.clientId())).issuedAt(issuedAt).claim("jti", eventId)
            .claim("events", replacements.getOrDefault("events", events));
        if (sessionId != null) {
            builder.claim("sid", sessionId);
        }
        replacements.forEach((name, value) -> {
            if (!"events".equals(name)) {
                builder.claim(name, value);
            }
        });
        return builder.build();
    }

    private static final class FakeIndex implements OidcSessionIndex {
        private final Set<Long> bySession;
        private final Set<Long> bySubject;
        private final Set<String> completed = new LinkedHashSet<>();
        private final Set<String> released = new LinkedHashSet<>();
        private int sessionLookups;
        private int subjectLookups;

        private FakeIndex(Set<Long> bySession, Set<Long> bySubject) {
            this.bySession = bySession;
            this.bySubject = bySubject;
        }

        @Override
        public void register(String issuer, String subject, String sessionId, long membershipId) { }

        @Override
        public Set<Long> findBySession(String issuer, String sessionId) {
            sessionLookups++;
            return bySession;
        }

        @Override
        public Set<Long> findBySubject(String issuer, String subject) {
            subjectLookups++;
            return bySubject;
        }

        @Override
        public EventClaim claimEvent(String issuer, String eventId) {
            return completed.contains(eventId) ? EventClaim.completed() : EventClaim.acquired("owner-1");
        }

        @Override
        public void completeEvent(String issuer, String eventId, String owner) {
            completed.add(eventId);
        }

        @Override
        public void releaseEvent(String issuer, String eventId, String owner) {
            released.add(eventId);
        }
    }
}
