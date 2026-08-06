package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcFlowServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T04:00:00Z");
    private static final String HOST = "ops.example.com";

    @Test
    void startUsesOnlyARegisteredHostAndPersistsOneTimeProtocolMaterial() {
        Fixture fixture = new Fixture();

        OidcFlowService.StartResult result = fixture.service.start(HOST);

        assertThat(result.authorizationUri()).isEqualTo(URI.create("https://idp.example.test/authorize"));
        assertThat(fixture.transactions.values).hasSize(1);
        OidcFlowService.LoginTransaction transaction = fixture.transactions.values.values().iterator().next();
        assertThat(transaction.entryHost()).isEqualTo(HOST);
        assertThat(transaction.codeVerifier()).isEqualTo("verifier");
        assertThat(transaction.nonce()).isNotBlank();
        assertThatThrownBy(() -> fixture.service.start("unknown.example.com"))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    @Test
    void callbackAndHandoffAreSingleUseAndBoundToTheRegisteredHost() {
        Fixture fixture = new Fixture();
        OidcFlowService.StartResult start = fixture.service.start(HOST);

        OidcFlowService.CallbackResult callback = fixture.service.callback("code", start.state());

        assertThat(callback.redirectUri().getHost()).isEqualTo(HOST);
        assertThat(callback.redirectUri().getRawQuery()).startsWith("handoff=");
        assertThatThrownBy(() -> fixture.service.callback("code", start.state()))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);

        String handoff = callback.redirectUri().getRawQuery().substring("handoff=".length());
        OidcFlowService.LoginResult login = fixture.service.redeem(handoff, HOST);
        assertThat(login.marker()).isEqualTo("cookie-session");
        assertThat(fixture.authenticator.lastIdentity.subject()).isEqualTo("subject-1");
        assertThatThrownBy(() -> fixture.service.redeem(handoff, HOST))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    @Test
    void aWrongHostConsumesTheOpaqueHandoffAndCannotBeRetried() {
        Fixture fixture = new Fixture();
        OidcFlowService.StartResult start = fixture.service.start(HOST);
        OidcFlowService.CallbackResult callback = fixture.service.callback("code", start.state());
        String handoff = callback.redirectUri().getRawQuery().substring("handoff=".length());

        assertThatThrownBy(() -> fixture.service.redeem(handoff, "attacker.example.com"))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
        assertThatThrownBy(() -> fixture.service.redeem(handoff, HOST))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    @Test
    void providerErrorConsumesStateAndPlainHttpCannotTargetAProductionHost() {
        Fixture fixture = new Fixture();
        OidcFlowService.StartResult start = fixture.service.start(HOST);

        assertThatThrownBy(() -> fixture.service.rejectCallback(start.state()))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
        assertThatThrownBy(() -> fixture.service.callback("code", start.state()))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);

        OidcFlowService insecure = fixture.service("http");
        assertThatThrownBy(() -> insecure.start(HOST))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    private static final class Fixture {
        final InMemoryTransactions transactions = new InMemoryTransactions();
        final InMemoryHandoffs handoffs = new InMemoryHandoffs();
        final RecordingAuthenticator authenticator = new RecordingAuthenticator();
        final OidcFlowService service = service("https");

        OidcFlowService service(String scheme) {
            return new OidcFlowService(
                AccountDomain.PLATFORM,
                host -> HOST.equals(host)
                    ? Optional.of(new OidcFlowService.TrustedEntry(HOST, AccountDomain.PLATFORM, 1L))
                    : Optional.empty(),
                (state, nonce) -> new OidcFlowService.AuthorizationRequest(
                    URI.create("https://idp.example.test/authorize"), "verifier"),
                (code, transaction) -> new OidcFlowService.AuthenticatedIdentity(
                    "https://idp.example.test/realms/PLATFORM", "subject-1", "session-1",
                    NOW.minusSeconds(30), "2", "signed-id-token"),
                transactions,
                handoffs,
                authenticator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "opaque-" + (transactions.values.size() + handoffs.values.size()),
                scheme,
                "/auth/oidc/callback");
        }
    }

    private static final class InMemoryTransactions implements OidcFlowService.LoginTransactionStore {
        final Map<String, OidcFlowService.LoginTransaction> values = new HashMap<>();

        @Override
        public void putTransaction(String state, OidcFlowService.LoginTransaction transaction) {
            values.put(state, transaction);
        }

        @Override
        public Optional<OidcFlowService.LoginTransaction> takeTransaction(String state) {
            return Optional.ofNullable(values.remove(state));
        }
    }

    private static final class InMemoryHandoffs implements OidcFlowService.HandoffStore {
        final Map<String, OidcFlowService.LoginHandoff> values = new HashMap<>();

        @Override
        public void putHandoff(String code, OidcFlowService.LoginHandoff handoff) {
            values.put(code, handoff);
        }

        @Override
        public Optional<OidcFlowService.LoginHandoff> takeHandoff(String code) {
            return Optional.ofNullable(values.remove(code));
        }
    }

    private static final class RecordingAuthenticator implements OidcFlowService.SessionAuthenticator {
        OidcFlowService.AuthenticatedIdentity lastIdentity;

        @Override
        public void authenticate(OidcFlowService.TrustedEntry entry,
                                 OidcFlowService.AuthenticatedIdentity identity) {
            lastIdentity = identity;
        }
    }
}
