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

class OidcStepUpFlowServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T04:00:00Z");
    private static final String HOST = "ops.example.com";

    @Test
    void bindsStepUpToTheExistingPrincipalAndApplicationSession() {
        Fixture fixture = new Fixture();

        OidcStepUpFlowService.StartResult start = fixture.service.start(HOST);
        assertThat(start.authorizationUri()).isEqualTo(URI.create("https://idp.example.test/step-up"));
        assertThat(start.state()).startsWith("stepup.");

        OidcStepUpFlowService.CallbackResult callback = fixture.service.callback("code", start.state());
        String handoff = callback.redirectUri().getRawQuery().substring("stepup=".length());
        OidcStepUpFlowService.StepUpResult result = fixture.service.redeem(handoff, HOST);

        assertThat(result.stepUpAt()).isEqualTo(NOW);
        assertThat(fixture.sessions.completedAt).isEqualTo(NOW);
        assertThatThrownBy(() -> fixture.service.redeem(handoff, HOST))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    @Test
    void rejectsIdentityChangesStaleAuthenticationAndSessionReplacement() {
        Fixture subjectChanged = new Fixture();
        OidcStepUpFlowService.StartResult subjectStart = subjectChanged.service.start(HOST);
        subjectChanged.identity = identity("other-subject", NOW);
        assertThatThrownBy(() -> subjectChanged.service.callback("code", subjectStart.state()))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);

        Fixture stale = new Fixture();
        OidcStepUpFlowService.StartResult staleStart = stale.service.start(HOST);
        stale.identity = identity("subject-1", NOW.minusSeconds(120));
        assertThatThrownBy(() -> stale.service.callback("code", staleStart.state()))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);

        Fixture replaced = new Fixture();
        OidcStepUpFlowService.StartResult replacedStart = replaced.service.start(HOST);
        String handoff = replaced.service.callback("code", replacedStart.state())
            .redirectUri().getRawQuery().substring("stepup=".length());
        replaced.sessions.current = principal("different-session-binding");
        assertThatThrownBy(() -> replaced.service.redeem(handoff, HOST))
            .isInstanceOf(OidcFlowService.LoginRejectedException.class);
    }

    private static OidcFlowService.AuthenticatedIdentity identity(String subject, Instant authTime) {
        return new OidcFlowService.AuthenticatedIdentity(
            "https://idp.example.test/realms/PLATFORM", subject, "session-2",
            authTime, "2", "signed-step-up-token");
    }

    private static OidcStepUpFlowService.StepUpPrincipal principal(String binding) {
        return new OidcStepUpFlowService.StepUpPrincipal(AccountDomain.PLATFORM, 1L, 10L, 20L,
            HOST, "https://idp.example.test/realms/PLATFORM", "subject-1", binding);
    }

    private final class Fixture {
        final InMemoryStore store = new InMemoryStore();
        final RecordingSessions sessions = new RecordingSessions();
        OidcFlowService.AuthenticatedIdentity identity = identity("subject-1", NOW);
        final OidcStepUpFlowService service = new OidcStepUpFlowService(
            AccountDomain.PLATFORM,
            host -> HOST.equals(host)
                ? Optional.of(new OidcFlowService.TrustedEntry(HOST, AccountDomain.PLATFORM, 1L))
                : Optional.empty(),
            (state, nonce) -> new OidcFlowService.AuthorizationRequest(
                URI.create("https://idp.example.test/step-up"), "verifier"),
            (code, transaction) -> identity,
            store, store, sessions, Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "opaque-" + (store.transactions.size() + store.handoffs.size()),
            "https", "/auth/oidc/callback");
    }

    private static final class InMemoryStore implements OidcStepUpFlowService.TransactionStore,
        OidcStepUpFlowService.HandoffStore {
        private final Map<String, OidcStepUpFlowService.StepUpTransaction> transactions = new HashMap<>();
        private final Map<String, OidcStepUpFlowService.StepUpHandoff> handoffs = new HashMap<>();

        @Override
        public void putStepUpTransaction(String state, OidcStepUpFlowService.StepUpTransaction transaction) {
            transactions.put(state, transaction);
        }

        @Override
        public Optional<OidcStepUpFlowService.StepUpTransaction> takeStepUpTransaction(String state) {
            return Optional.ofNullable(transactions.remove(state));
        }

        @Override
        public void putStepUpHandoff(String code, OidcStepUpFlowService.StepUpHandoff handoff) {
            handoffs.put(code, handoff);
        }

        @Override
        public Optional<OidcStepUpFlowService.StepUpHandoff> takeStepUpHandoff(String code) {
            return Optional.ofNullable(handoffs.remove(code));
        }
    }

    private static final class RecordingSessions implements OidcStepUpFlowService.SessionStepUp {
        private OidcStepUpFlowService.StepUpPrincipal current = principal("session-binding");
        private Instant completedAt;

        @Override
        public OidcStepUpFlowService.StepUpPrincipal current(String requestHost) {
            return current;
        }

        @Override
        public void complete(OidcStepUpFlowService.StepUpPrincipal expected,
                             OidcFlowService.AuthenticatedIdentity identity, Instant completedAt) {
            this.completedAt = completedAt;
        }
    }
}
