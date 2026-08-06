package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.IdentityInvitationRelayRepository;
import com.niv.payment.identity.lifecycle.IdentityInvitationStep;
import com.niv.payment.identity.lifecycle.IdentityInvitationTask;
import com.niv.payment.permission.domain.AccountDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class IdentityInvitationRelay {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityInvitationRelay.class);

    private final AccountDomain accountDomain;
    private final IdentityInvitationRelayRepository repository;
    private final KeycloakActions keycloak;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration retryDelay;

    IdentityInvitationRelay(AccountDomain accountDomain,
                            IdentityInvitationRelayRepository repository,
                            KeycloakActions keycloak,
                            Clock clock,
                            Duration leaseDuration,
                            Duration retryDelay) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.retryDelay = requirePositive(retryDelay, "retryDelay");
    }

    void runOnce() {
        Instant now = clock.instant();
        var claimed = repository.claimNext(accountDomain, now, leaseDuration);
        if (claimed.isEmpty()) {
            return;
        }
        IdentityInvitationTask task = claimed.orElseThrow();
        try {
            execute(task);
            repository.completeStep(task.invitationId(), task.nextStep(), clock.instant());
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof IdentityProvisioningException provisioning
                ? provisioning.errorCode() : "UNCLASSIFIED_FAILURE";
            repository.reschedule(task.invitationId(), clock.instant().plus(retryDelay), errorCode);
            LOG.warn("Identity invitation relay deferred invitationId={} step={} errorCode={}",
                task.invitationId(), task.nextStep(), errorCode);
        }
    }

    private void execute(IdentityInvitationTask task) {
        switch (task.nextStep()) {
            case KEYCLOAK_ENABLE -> keycloak.enable(task);
            case ACTION_EMAIL -> keycloak.sendActionEmail(task);
            case APPLICATION_ACTIVATION -> {
                // The repository atomically activates the local identity boundary for this step.
            }
        }
    }

    interface KeycloakActions {
        void enable(IdentityInvitationTask task);

        void sendActionEmail(IdentityInvitationTask task);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
