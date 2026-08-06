package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.MfaRecoveryTask;
import com.niv.payment.permission.domain.AccountDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class MfaRecoveryRelay {
    private static final Logger LOG = LoggerFactory.getLogger(MfaRecoveryRelay.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);

    private final AccountDomain accountDomain;
    private final MfaRecoveryRepository repository;
    private final KeycloakActions keycloak;
    private final ApplicationSessionActions applicationSessions;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration baseRetryDelay;

    MfaRecoveryRelay(AccountDomain accountDomain, MfaRecoveryRepository repository,
                     KeycloakActions keycloak, ApplicationSessionActions applicationSessions,
                     Clock clock, Duration leaseDuration, Duration baseRetryDelay) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak");
        this.applicationSessions = Objects.requireNonNull(applicationSessions, "applicationSessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.baseRetryDelay = positive(baseRetryDelay, "baseRetryDelay");
    }

    boolean runOnce() {
        Instant now = clock.instant();
        var claimed = repository.claimNext(accountDomain, now, leaseDuration);
        if (claimed.isEmpty()) {
            return false;
        }
        MfaRecoveryTask task = claimed.orElseThrow();
        try {
            switch (task.nextStep()) {
                case MFA_CREDENTIALS -> keycloak.revokeMfaCredentials(task);
                case RECOVERY_CODES -> keycloak.revokeRecoveryCodes(task);
                case KEYCLOAK_SESSIONS -> keycloak.revokeKeycloakSessions(task);
                case APPLICATION_SESSIONS -> applicationSessions.revoke(task);
            }
            repository.completeStep(task.recoveryId(), task.nextStep(), clock.instant());
        } catch (RuntimeException exception) {
            String errorCode = errorCode(exception);
            repository.reschedule(task.recoveryId(), now.plus(retryDelay(task.attempt())), errorCode);
            LOG.warn("MFA recovery step failed: recoveryId={}, domain={}, step={}, errorCode={}",
                task.recoveryId(), task.accountDomain(), task.nextStep(), errorCode);
        }
        return true;
    }

    private Duration retryDelay(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 10);
        long seconds;
        try {
            seconds = Math.multiplyExact(baseRetryDelay.toSeconds(), 1L << exponent);
        } catch (ArithmeticException exception) {
            return MAX_RETRY_DELAY;
        }
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof KeycloakMfaRecoveryClient.MfaRecoveryActionException action) {
            return action.errorCode();
        }
        if (exception instanceof ApplicationSessionRevocationException) {
            return "APPLICATION_SESSION_REVOCATION_FAILED";
        }
        return "MFA_RECOVERY_STEP_FAILED";
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    interface KeycloakActions {
        void revokeMfaCredentials(MfaRecoveryTask task);
        void revokeRecoveryCodes(MfaRecoveryTask task);
        void revokeKeycloakSessions(MfaRecoveryTask task);
    }

    interface ApplicationSessionActions {
        void revoke(MfaRecoveryTask task);
    }

    static final class ApplicationSessionRevocationException extends RuntimeException {
        ApplicationSessionRevocationException(Throwable cause) {
            super(cause);
        }
    }
}
