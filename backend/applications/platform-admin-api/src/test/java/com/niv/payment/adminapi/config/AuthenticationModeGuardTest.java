package com.niv.payment.adminapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationModeGuardTest {
    @Test
    void permitsOnlyLocalPasswordModeInsideTheLocalProfile() {
        assertThatCode(() -> new AuthenticationModeGuard(true, true, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new AuthenticationModeGuard(false, true, false))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRequiresOidcAndRejectsMixedAuthenticationModes() {
        assertThatCode(() -> new AuthenticationModeGuard(false, false, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new AuthenticationModeGuard(false, false, false))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthenticationModeGuard(true, true, true))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadsThePlatformConfidentialClientValueOnlyFromTheCompositionRootEnvironment() {
        MockEnvironment environment = new MockEnvironment().withProperty(
            "PAYMENT_PLATFORM_OIDC_CLIENT_" + "SECRET", "synthetic-test-value");

        assertThat(new IdentityConfiguration().oidcClientCredential(environment).value())
            .isEqualTo("synthetic-test-value");
        assertThatThrownBy(() -> new IdentityConfiguration()
            .oidcClientCredential(new MockEnvironment()))
            .isInstanceOf(IllegalStateException.class);
    }
}
