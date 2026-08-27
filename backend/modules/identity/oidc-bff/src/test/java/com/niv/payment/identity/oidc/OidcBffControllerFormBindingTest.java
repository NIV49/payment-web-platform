package com.niv.payment.identity.oidc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcBffControllerFormBindingTest {
    @Test
    void backChannelLogoutUsesTheReconstructedServletParameterContract() {
        Method method = Arrays.stream(OidcBffController.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("backChannelLogout"))
            .findFirst().orElseThrow();

        assertThat(method.getParameterTypes()).containsExactly(jakarta.servlet.http.HttpServletRequest.class);
        assertThat(OidcBffController.requireLogoutToken(
            Map.of("logout_token", new String[] {"signed-token"})))
            .isEqualTo("signed-token");
    }

    @Test
    void acceptsExactlyOneBoundedLogoutToken() {
        var form = Map.of("logout_token", new String[] {"signed-token"});
        assertThat(OidcBffController.requireLogoutToken(form)).isEqualTo("signed-token");

        assertRejected(Map.of("logout_token", new String[] {"signed-token", "duplicate"}));
        assertRejected(Map.of(
            "logout_token", new String[] {"signed-token"},
            "unexpected", new String[] {"value"}));
        assertRejected(Map.of("logout_token", new String[] {"x".repeat(16_385)}));
        assertRejected(null);
    }

    private static void assertRejected(Map<String, String[]> form) {
        assertThatThrownBy(() -> OidcBffController.requireLogoutToken(form))
            .isInstanceOf(OidcLogoutTokenVerifier.BackChannelLogoutRejectedException.class);
    }
}
