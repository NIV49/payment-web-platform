package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class OidcStepUpFlowService {
    private static final String STATE_PREFIX = "stepup.";
    private static final Duration AUTH_TIME_SKEW = Duration.ofSeconds(60);
    private static final Pattern CANONICAL_HOST = Pattern.compile(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    private final AccountDomain accountDomain;
    private final OidcFlowService.TrustedEntryResolver entries;
    private final AuthorizationClient authorizationClient;
    private final CodeExchangeClient codeExchangeClient;
    private final TransactionStore transactions;
    private final HandoffStore handoffs;
    private final SessionStepUp sessions;
    private final Clock clock;
    private final Supplier<String> opaqueValue;
    private final String publicScheme;
    private final String frontendCallbackPath;

    public OidcStepUpFlowService(AccountDomain accountDomain,
                                 OidcFlowService.TrustedEntryResolver entries,
                                 AuthorizationClient authorizationClient,
                                 CodeExchangeClient codeExchangeClient,
                                 TransactionStore transactions,
                                 HandoffStore handoffs,
                                 SessionStepUp sessions,
                                 Clock clock,
                                 Supplier<String> opaqueValue,
                                 String publicScheme,
                                 String frontendCallbackPath) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.authorizationClient = Objects.requireNonNull(authorizationClient, "authorizationClient");
        this.codeExchangeClient = Objects.requireNonNull(codeExchangeClient, "codeExchangeClient");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.opaqueValue = Objects.requireNonNull(opaqueValue, "opaqueValue");
        this.publicScheme = requireScheme(publicScheme);
        this.frontendCallbackPath = requireCallbackPath(frontendCallbackPath);
    }

    public StartResult start(String requestHost) {
        String host = canonicalHost(requestHost);
        StepUpPrincipal principal = sessions.current(host);
        OidcFlowService.TrustedEntry entry = entries.findActive(host)
            .filter(candidate -> candidate.accountDomain() == accountDomain
                && candidate.tenantId() == principal.tenantId())
            .orElseThrow(OidcFlowService.LoginRejectedException::new);
        if (principal.accountDomain() != accountDomain || !principal.entryHost().equals(host)) {
            throw new OidcFlowService.LoginRejectedException();
        }
        if ("http".equals(publicScheme) && !isLoopback(host)) {
            throw new OidcFlowService.LoginRejectedException();
        }
        String state = STATE_PREFIX + requireOpaque(opaqueValue.get());
        String nonce = requireOpaque(opaqueValue.get());
        OidcFlowService.AuthorizationRequest request = authorizationClient.beginStepUp(state, nonce);
        transactions.putStepUpTransaction(state, new StepUpTransaction(
            entry, principal, state, request.codeVerifier(), nonce, clock.instant()));
        return new StartResult(state, request.authorizationUri());
    }

    public CallbackResult callback(String code, String state) {
        String safeState = requireStepUpState(state);
        StepUpTransaction transaction = transactions.takeStepUpTransaction(safeState)
            .orElseThrow(OidcFlowService.LoginRejectedException::new);
        OidcFlowService.AuthenticatedIdentity identity;
        try {
            identity = codeExchangeClient.exchange(requirePresentedValue(code), transaction);
        } catch (OidcFlowService.LoginRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OidcFlowService.LoginRejectedException(exception);
        }
        if (!transaction.principal().issuer().equals(identity.issuer())
            || !transaction.principal().subject().equals(identity.subject())
            || identity.authTime().isBefore(transaction.createdAt().minus(AUTH_TIME_SKEW))) {
            throw new OidcFlowService.LoginRejectedException();
        }
        String handoff = requireOpaque(opaqueValue.get());
        handoffs.putStepUpHandoff(handoff, new StepUpHandoff(transaction, identity, clock.instant()));
        return new CallbackResult(frontendRedirect(transaction.entry().entryHost(), "stepup", handoff));
    }

    public void rejectCallback(String state) {
        transactions.takeStepUpTransaction(requireStepUpState(state));
        throw new OidcFlowService.LoginRejectedException();
    }

    public StepUpResult redeem(String code, String requestHost) {
        StepUpHandoff handoff = handoffs.takeStepUpHandoff(requirePresentedValue(code))
            .orElseThrow(OidcFlowService.LoginRejectedException::new);
        String host = canonicalHost(requestHost);
        StepUpPrincipal current = sessions.current(host);
        if (!handoff.transaction().entry().entryHost().equals(host)
            || !handoff.transaction().principal().equals(current)) {
            throw new OidcFlowService.LoginRejectedException();
        }
        Instant completedAt = clock.instant();
        sessions.complete(handoff.transaction().principal(), handoff.identity(), completedAt);
        return new StepUpResult(completedAt);
    }

    public static boolean isStepUpState(String state) {
        return state != null && state.startsWith(STATE_PREFIX);
    }

    private URI frontendRedirect(String host, String parameter, String value) {
        return URI.create(publicScheme + "://" + host + frontendCallbackPath + "?" + parameter + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String canonicalHost(String value) {
        if (value == null) {
            throw new OidcFlowService.LoginRejectedException();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_HOST.matcher(normalized).matches()) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return normalized;
    }

    private static String requireStepUpState(String value) {
        String state = requirePresentedValue(value);
        if (!isStepUpState(state)) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return state;
    }

    private static String requirePresentedValue(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return value;
    }

    private static String requireOpaque(String value) {
        String opaque = requirePresentedValue(value);
        if (opaque.startsWith(STATE_PREFIX)) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return opaque;
    }

    private static String requireScheme(String value) {
        if (!"https".equals(value) && !"http".equals(value)) {
            throw new IllegalArgumentException("Public scheme must be http or https");
        }
        return value;
    }

    private static String requireCallbackPath(String value) {
        if (value == null || !value.startsWith("/") || value.contains("?") || value.contains("#")) {
            throw new IllegalArgumentException("Frontend callback must be an absolute path");
        }
        return value;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "::1".equals(host) || "[::1]".equals(host)
            || host.startsWith("127.");
    }

    public record StepUpPrincipal(AccountDomain accountDomain, long tenantId, long userId,
                                  long membershipId, String entryHost, String issuer,
                                  String subject, String applicationSessionBinding) {
        public StepUpPrincipal {
            Objects.requireNonNull(accountDomain, "accountDomain");
            if (tenantId <= 0 || userId <= 0 || membershipId <= 0) {
                throw new IllegalArgumentException("Step-up principal identifiers must be positive");
            }
            entryHost = canonicalHost(entryHost);
            issuer = requirePresentedValue(issuer);
            subject = requirePresentedValue(subject);
            applicationSessionBinding = requirePresentedValue(applicationSessionBinding);
        }
    }

    public record StepUpTransaction(OidcFlowService.TrustedEntry entry, StepUpPrincipal principal,
                                    String state, String codeVerifier, String nonce,
                                    Instant createdAt) {
        public StepUpTransaction {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(principal, "principal");
            state = requireStepUpState(state);
            codeVerifier = requirePresentedValue(codeVerifier);
            nonce = requirePresentedValue(nonce);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record StepUpHandoff(StepUpTransaction transaction,
                                OidcFlowService.AuthenticatedIdentity identity,
                                Instant createdAt) {
        public StepUpHandoff {
            Objects.requireNonNull(transaction, "transaction");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record StartResult(String state, URI authorizationUri) { }
    public record CallbackResult(URI redirectUri) { }
    public record StepUpResult(Instant stepUpAt) { }

    @FunctionalInterface
    public interface AuthorizationClient {
        OidcFlowService.AuthorizationRequest beginStepUp(String state, String nonce);
    }

    @FunctionalInterface
    public interface CodeExchangeClient {
        OidcFlowService.AuthenticatedIdentity exchange(String code, StepUpTransaction transaction);
    }

    public interface TransactionStore {
        void putStepUpTransaction(String state, StepUpTransaction transaction);
        Optional<StepUpTransaction> takeStepUpTransaction(String state);
    }

    public interface HandoffStore {
        void putStepUpHandoff(String code, StepUpHandoff handoff);
        Optional<StepUpHandoff> takeStepUpHandoff(String code);
    }

    public interface SessionStepUp {
        StepUpPrincipal current(String requestHost);
        void complete(StepUpPrincipal expected, OidcFlowService.AuthenticatedIdentity identity,
                      Instant completedAt);
    }
}
