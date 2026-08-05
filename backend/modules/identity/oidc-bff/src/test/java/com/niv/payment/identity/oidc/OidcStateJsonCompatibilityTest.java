package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OidcStateJsonCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void loginTransactionAndHandoffRoundTripThroughTheRuntimeMapper() throws Exception {
        OidcFlowService.TrustedEntry entry = new OidcFlowService.TrustedEntry(
            "ops.example.com", AccountDomain.PLATFORM, 11L);
        OidcFlowService.LoginTransaction transaction = new OidcFlowService.LoginTransaction(
            entry, "state", "verifier", "nonce", Instant.parse("2026-08-05T04:00:00Z"));
        OidcFlowService.LoginHandoff handoff = new OidcFlowService.LoginHandoff(entry,
            new OidcFlowService.AuthenticatedIdentity(
                "https://idp.example.test/realms/PLATFORM", "subject", "session",
                Instant.parse("2026-08-05T03:59:30Z"), "2", "id-token"),
            Instant.parse("2026-08-05T04:00:01Z"));

        assertThat(roundTrip(transaction, OidcFlowService.LoginTransaction.class)).isEqualTo(transaction);
        assertThat(roundTrip(handoff, OidcFlowService.LoginHandoff.class)).isEqualTo(handoff);
    }

    @Test
    void stepUpTransactionAndHandoffRoundTripThroughTheRuntimeMapper() throws Exception {
        OidcFlowService.TrustedEntry entry = new OidcFlowService.TrustedEntry(
            "ops.example.com", AccountDomain.PLATFORM, 11L);
        OidcStepUpFlowService.StepUpPrincipal principal = new OidcStepUpFlowService.StepUpPrincipal(
            AccountDomain.PLATFORM, 11L, 12L, 13L, "ops.example.com",
            "https://idp.example.test/realms/PLATFORM", "subject", "session-binding");
        OidcStepUpFlowService.StepUpTransaction transaction =
            new OidcStepUpFlowService.StepUpTransaction(entry, principal, "stepup.state", "verifier",
                "nonce", Instant.parse("2026-08-05T04:00:00Z"));
        OidcStepUpFlowService.StepUpHandoff handoff = new OidcStepUpFlowService.StepUpHandoff(
            transaction,
            new OidcFlowService.AuthenticatedIdentity(
                "https://idp.example.test/realms/PLATFORM", "subject", "session",
                Instant.parse("2026-08-05T04:00:01Z"), "2", "id-token"),
            Instant.parse("2026-08-05T04:00:02Z"));

        assertThat(roundTrip(transaction, OidcStepUpFlowService.StepUpTransaction.class))
            .isEqualTo(transaction);
        assertThat(roundTrip(handoff, OidcStepUpFlowService.StepUpHandoff.class)).isEqualTo(handoff);
    }

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return json.readValue(json.writeValueAsString(value), type);
    }
}
