package com.niv.payment.identity.oidc;

import java.util.Objects;
import java.util.Set;

public final class OidcBackChannelLogoutService {
    private final OidcLogoutTokenVerifier verifier;
    private final OidcSessionIndex index;
    private final ApplicationSessionRevoker sessions;

    public OidcBackChannelLogoutService(OidcLogoutTokenVerifier verifier, OidcSessionIndex index,
                                        ApplicationSessionRevoker sessions) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.index = Objects.requireNonNull(index, "index");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public void logout(String signedLogout) {
        OidcLogoutTokenVerifier.LogoutIdentity identity = verifier.verify(signedLogout);
        OidcSessionIndex.EventClaim claim = index.claimEvent(identity.issuer(), identity.eventId());
        if (claim.status() == OidcSessionIndex.Status.COMPLETED) {
            return;
        }
        if (claim.status() == OidcSessionIndex.Status.IN_PROGRESS) {
            throw new IllegalStateException("OIDC logout event is already being processed");
        }
        try {
            Set<Long> memberships = identity.sessionId() == null
                ? index.findBySubject(identity.issuer(), identity.subject())
                : index.findBySession(identity.issuer(), identity.sessionId());
            memberships.forEach(sessions::revoke);
            index.completeEvent(identity.issuer(), identity.eventId(), claim.owner());
        } catch (RuntimeException exception) {
            index.releaseEvent(identity.issuer(), identity.eventId(), claim.owner());
            throw exception;
        }
    }

    @FunctionalInterface
    public interface ApplicationSessionRevoker {
        void revoke(long membershipId);
    }
}
