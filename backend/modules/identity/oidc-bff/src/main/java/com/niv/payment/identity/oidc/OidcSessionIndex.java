package com.niv.payment.identity.oidc;

import java.util.Set;

public interface OidcSessionIndex {
    void register(String issuer, String subject, String sessionId, long membershipId);

    Set<Long> findBySession(String issuer, String sessionId);

    Set<Long> findBySubject(String issuer, String subject);

    EventClaim claimEvent(String issuer, String eventId);

    void completeEvent(String issuer, String eventId, String owner);

    void releaseEvent(String issuer, String eventId, String owner);

    record EventClaim(Status status, String owner) {
        public EventClaim {
            if ((status == Status.ACQUIRED) != (owner != null && !owner.isBlank())) {
                throw new IllegalArgumentException("Only an acquired event can have an owner");
            }
        }

        static EventClaim acquired(String owner) {
            return new EventClaim(Status.ACQUIRED, owner);
        }

        static EventClaim inProgress() {
            return new EventClaim(Status.IN_PROGRESS, null);
        }

        static EventClaim completed() {
            return new EventClaim(Status.COMPLETED, null);
        }
    }

    enum Status {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }
}
