package com.niv.payment.permission.backoffice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackofficeAuthenticationModeGuardTest {
    @Test
    void localProfileRequiresOnlyLocalPasswordLogin() {
        assertThatCode(() -> new BackofficeAuthenticationModeGuard(true, true, false))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> new BackofficeAuthenticationModeGuard(true, false, true))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRequiresOnlyOidc() {
        assertThatCode(() -> new BackofficeAuthenticationModeGuard(false, false, true))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> new BackofficeAuthenticationModeGuard(false, true, false))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ambiguousModesFailClosed() {
        assertThatThrownBy(() -> new BackofficeAuthenticationModeGuard(false, false, false))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BackofficeAuthenticationModeGuard(true, true, true))
            .isInstanceOf(IllegalStateException.class);
    }
}
