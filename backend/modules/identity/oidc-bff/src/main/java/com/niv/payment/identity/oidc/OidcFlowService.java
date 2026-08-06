package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class OidcFlowService {
    private static final Pattern CANONICAL_HOST = Pattern.compile(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    private final AccountDomain accountDomain;
    private final TrustedEntryResolver entries;
    private final AuthorizationClient authorizationClient;
    private final CodeExchangeClient codeExchangeClient;
    private final LoginTransactionStore transactions;
    private final HandoffStore handoffs;
    private final SessionAuthenticator authenticator;
    private final Clock clock;
    private final Supplier<String> opaqueValue;
    private final String publicScheme;
    private final String frontendCallbackPath;

    public OidcFlowService(AccountDomain accountDomain,
                           TrustedEntryResolver entries,
                           AuthorizationClient authorizationClient,
                           CodeExchangeClient codeExchangeClient,
                           LoginTransactionStore transactions,
                           HandoffStore handoffs,
                           SessionAuthenticator authenticator,
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
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.opaqueValue = Objects.requireNonNull(opaqueValue, "opaqueValue");
        this.publicScheme = requireScheme(publicScheme);
        this.frontendCallbackPath = requireCallbackPath(frontendCallbackPath);
    }

    public StartResult start(String requestHost) {
        String host = canonicalHost(requestHost);
        TrustedEntry entry = entries.findActive(host)
            .filter(candidate -> candidate.accountDomain() == accountDomain)
            .orElseThrow(LoginRejectedException::new);
        if ("http".equals(publicScheme) && !isLoopback(host)) {
            throw new LoginRejectedException();
        }
        String state = requireOpaque(opaqueValue.get());
        String nonce = requireOpaque(opaqueValue.get());
        AuthorizationRequest request = authorizationClient.begin(state, nonce);
        transactions.putTransaction(state, new LoginTransaction(
            entry, state, request.codeVerifier(), nonce, clock.instant()));
        return new StartResult(state, request.authorizationUri());
    }

    public CallbackResult callback(String code, String state) {
        String safeState = requirePresentedValue(state);
        LoginTransaction transaction = transactions.takeTransaction(safeState)
            .orElseThrow(LoginRejectedException::new);
        AuthenticatedIdentity identity;
        try {
            identity = codeExchangeClient.exchange(requirePresentedValue(code), transaction);
        } catch (LoginRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LoginRejectedException(exception);
        }
        String handoff = requireOpaque(opaqueValue.get());
        handoffs.putHandoff(handoff, new LoginHandoff(transaction.entry(), identity, clock.instant()));
        String encoded = URLEncoder.encode(handoff, StandardCharsets.UTF_8);
        return new CallbackResult(URI.create(publicScheme + "://" + transaction.entry().entryHost()
            + frontendCallbackPath + "?handoff=" + encoded));
    }

    public void rejectCallback(String state) {
        transactions.takeTransaction(requirePresentedValue(state));
        throw new LoginRejectedException();
    }

    public LoginResult redeem(String code, String requestHost) {
        LoginHandoff handoff = handoffs.takeHandoff(requirePresentedValue(code))
            .orElseThrow(LoginRejectedException::new);
        String host = canonicalHost(requestHost);
        if (!handoff.entry().entryHost().equals(host)
            || handoff.entry().accountDomain() != accountDomain) {
            throw new LoginRejectedException();
        }
        authenticator.authenticate(handoff.entry(), handoff.identity());
        return new LoginResult("cookie-session");
    }

    private static String canonicalHost(String value) {
        if (value == null) {
            throw new LoginRejectedException();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_HOST.matcher(normalized).matches()) {
            throw new LoginRejectedException();
        }
        return normalized;
    }

    private static String requirePresentedValue(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new LoginRejectedException();
        }
        return value;
    }

    private static String requireOpaque(String value) {
        return requirePresentedValue(value);
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

    public record TrustedEntry(String entryHost, AccountDomain accountDomain, long tenantId) {
        public TrustedEntry {
            entryHost = canonicalHost(entryHost);
            Objects.requireNonNull(accountDomain, "accountDomain");
            if (tenantId <= 0) {
                throw new IllegalArgumentException("Tenant id must be positive");
            }
        }
    }

    public record AuthorizationRequest(URI authorizationUri, String codeVerifier) {
        public AuthorizationRequest {
            Objects.requireNonNull(authorizationUri, "authorizationUri");
            codeVerifier = requirePresentedValue(codeVerifier);
        }
    }

    public record AuthenticatedIdentity(String issuer, String subject, String sessionId,
                                        Instant authTime, String acr, String idToken) {
        public AuthenticatedIdentity {
            issuer = requirePresentedValue(issuer);
            subject = requirePresentedValue(subject);
            sessionId = requirePresentedValue(sessionId);
            Objects.requireNonNull(authTime, "authTime");
            acr = requirePresentedValue(acr);
            idToken = requirePresentedValue(idToken);
        }
    }

    public record LoginTransaction(TrustedEntry entry, String state, String codeVerifier, String nonce,
                                   Instant createdAt) {
        public LoginTransaction {
            Objects.requireNonNull(entry, "entry");
            state = requirePresentedValue(state);
            codeVerifier = requirePresentedValue(codeVerifier);
            nonce = requirePresentedValue(nonce);
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public String entryHost() {
            return entry.entryHost();
        }
    }

    public record LoginHandoff(TrustedEntry entry, AuthenticatedIdentity identity, Instant createdAt) {
        public LoginHandoff {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record StartResult(String state, URI authorizationUri) { }
    public record CallbackResult(URI redirectUri) { }
    public record LoginResult(String marker) { }

    @FunctionalInterface
    public interface TrustedEntryResolver {
        Optional<TrustedEntry> findActive(String canonicalHost);
    }

    @FunctionalInterface
    public interface AuthorizationClient {
        AuthorizationRequest begin(String state, String nonce);
    }

    @FunctionalInterface
    public interface CodeExchangeClient {
        AuthenticatedIdentity exchange(String code, LoginTransaction transaction);
    }

    public interface LoginTransactionStore {
        void putTransaction(String state, LoginTransaction transaction);
        Optional<LoginTransaction> takeTransaction(String state);
    }

    public interface HandoffStore {
        void putHandoff(String code, LoginHandoff handoff);
        Optional<LoginHandoff> takeHandoff(String code);
    }

    @FunctionalInterface
    public interface SessionAuthenticator {
        void authenticate(TrustedEntry entry, AuthenticatedIdentity identity);
    }

    public static final class LoginRejectedException extends RuntimeException {
        public LoginRejectedException() {
            super("OIDC login was rejected");
        }

        public LoginRejectedException(Throwable cause) {
            super("OIDC login was rejected", cause);
        }
    }
}
