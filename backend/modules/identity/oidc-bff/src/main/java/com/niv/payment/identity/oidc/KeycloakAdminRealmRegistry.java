package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

final class KeycloakAdminRealmRegistry {
    private final Map<AccountDomain, KeycloakAdminSettings> settings;

    private KeycloakAdminRealmRegistry(Map<AccountDomain, KeycloakAdminSettings> settings) {
        this.settings = Map.copyOf(settings);
    }

    static Builder builder(OidcClientSettings oidc) {
        return new Builder(oidc);
    }

    KeycloakAdminSettings require(AccountDomain accountDomain) {
        KeycloakAdminSettings result = settings.get(accountDomain);
        if (result == null) {
            throw new IdentityProvisioningException("KEYCLOAK_REALM_CLIENT_NOT_CONFIGURED");
        }
        return result;
    }

    static final class Builder {
        private final OidcClientSettings oidc;
        private final EnumMap<AccountDomain, KeycloakAdminSettings> settings =
            new EnumMap<>(AccountDomain.class);

        private Builder(OidcClientSettings oidc) {
            this.oidc = Objects.requireNonNull(oidc, "oidc");
        }

        Builder add(AccountDomain accountDomain, String clientId,
                    KeycloakAdminClientCredential credential) {
            Objects.requireNonNull(accountDomain, "accountDomain");
            if (settings.putIfAbsent(accountDomain,
                KeycloakAdminSettings.forRealm(oidc.issuer(), accountDomain, clientId, credential)) != null) {
                throw new IllegalArgumentException("Duplicate Keycloak Realm administration client");
            }
            return this;
        }

        KeycloakAdminRealmRegistry build() {
            if (settings.isEmpty()) {
                throw new IllegalArgumentException("At least one Keycloak Realm client is required");
            }
            return new KeycloakAdminRealmRegistry(settings);
        }
    }
}
