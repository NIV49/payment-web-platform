package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakAdminSettingsTest {
    @Test
    void derivesRealmAdminEndpointFromTheExactOidcIssuer() {
        KeycloakAdminSettings settings = KeycloakAdminSettings.from(oidc("MERCHANT"),
            AccountDomain.MERCHANT, "merchant-lifecycle", new KeycloakAdminClientCredential("secret"));

        assertThat(settings.adminBaseUri())
            .isEqualTo(URI.create("https://idp.example.test/admin/realms/MERCHANT"));
        assertThat(settings.clientId()).isEqualTo("merchant-lifecycle");
    }

    @Test
    void rejectsUsingAnotherAccountDomainsRealm() {
        assertThatThrownBy(() -> KeycloakAdminSettings.from(oidc("AGENT"),
            AccountDomain.MERCHANT, "merchant-lifecycle", new KeycloakAdminClientCredential("secret")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticStringsNeverExposeClientSecrets() {
        String loginSecret = "login-secret-that-must-not-appear";
        String lifecycleSecret = "lifecycle-secret-that-must-not-appear";
        OidcClientSettings oidc = oidc("MERCHANT", loginSecret);
        KeycloakAdminClientCredential credential =
            new KeycloakAdminClientCredential(lifecycleSecret);

        KeycloakAdminSettings settings = KeycloakAdminSettings.from(oidc,
            AccountDomain.MERCHANT, "merchant-lifecycle", credential);

        assertThat(oidc.toString()).doesNotContain(loginSecret).contains("[REDACTED]");
        assertThat(credential.toString()).doesNotContain(lifecycleSecret).contains("[REDACTED]");
        assertThat(settings.toString()).doesNotContain(lifecycleSecret).contains("[REDACTED]");
        assertThat(new OidcClientCredential(loginSecret).toString())
            .doesNotContain(loginSecret).contains("[REDACTED]");
    }

    private static OidcClientSettings oidc(String realm) {
        return oidc(realm, "secret");
    }

    private static OidcClientSettings oidc(String realm, String secret) {
        String issuer = "https://idp.example.test/realms/" + realm;
        return new OidcClientSettings(URI.create(issuer), URI.create(issuer + "/protocol/openid-connect/auth"),
            URI.create(issuer + "/protocol/openid-connect/token"),
            URI.create(issuer + "/protocol/openid-connect/certs"),
            URI.create(issuer + "/protocol/openid-connect/logout"), "client", secret,
            URI.create("https://api.example.test/api/auth/oidc/callback"),
            URI.create("https://web.example.test/login"), "2");
    }
}
