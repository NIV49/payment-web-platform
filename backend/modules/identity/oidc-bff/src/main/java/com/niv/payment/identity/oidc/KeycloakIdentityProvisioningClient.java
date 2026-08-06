package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.FederatedIdentity;
import com.niv.payment.identity.lifecycle.IdentityInvitationTask;
import com.niv.payment.identity.lifecycle.IdentityProvisioningPort;
import com.niv.payment.permission.domain.AccountDomain;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class KeycloakIdentityProvisioningClient
    implements IdentityProvisioningPort, IdentityInvitationRelay.KeycloakActions {
    private static final String INVITATION_ATTRIBUTE = "paymentInvitationId";
    private static final List<String> REQUIRED_ACTIONS =
        List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP");

    private final RestClient http;
    private final KeycloakAdminRealmRegistry realms;
    private final Duration actionLifespan;

    KeycloakIdentityProvisioningClient(RestClient http, KeycloakAdminRealmRegistry realms,
                                       Duration actionLifespan) {
        this.http = Objects.requireNonNull(http, "http");
        this.realms = Objects.requireNonNull(realms, "realms");
        if (actionLifespan == null || actionLifespan.isZero() || actionLifespan.isNegative()
            || actionLifespan.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Keycloak invitation action lifespan is invalid");
        }
        this.actionLifespan = actionLifespan;
    }

    @Override
    public FederatedIdentity resolveInvitationIdentity(AccountDomain accountDomain,
                                                       UUID idempotencyKey,
                                                       String email,
                                                       String displayName) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        KeycloakAdminSettings settings = realms.require(accountDomain);
        String opaqueUsername = "invite-" + idempotencyKey;
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        UserRepresentation reserved = exactUsername(settings, opaqueUsername);
        if (reserved != null) {
            requireReservedIdentity(reserved, idempotencyKey, normalizedEmail);
            return identity(settings, reserved, opaqueUsername, FederatedIdentity.Mode.NEW_DISABLED);
        }
        List<UserRepresentation> emailMatches = exactEmail(settings, normalizedEmail);
        if (emailMatches.size() > 1) {
            throw new IdentityProvisioningException("KEYCLOAK_EMAIL_AMBIGUOUS");
        }
        if (emailMatches.size() == 1) {
            UserRepresentation existing = emailMatches.getFirst();
            if (!Boolean.TRUE.equals(existing.enabled())) {
                throw new IdentityProvisioningException("KEYCLOAK_EXISTING_IDENTITY_DISABLED");
            }
            return identity(settings, existing, existing.username(),
                FederatedIdentity.Mode.EXISTING_ACTIVE);
        }
        createDisabled(settings, idempotencyKey, opaqueUsername, normalizedEmail, displayName);
        UserRepresentation created = exactUsername(settings, opaqueUsername);
        if (created == null) {
            throw new IdentityProvisioningException("KEYCLOAK_CREATED_IDENTITY_NOT_FOUND");
        }
        requireReservedIdentity(created, idempotencyKey, normalizedEmail);
        return identity(settings, created, opaqueUsername, FederatedIdentity.Mode.NEW_DISABLED);
    }

    @Override
    public void enable(IdentityInvitationTask task) {
        KeycloakAdminSettings settings = validateTask(task);
        execute(() -> {
            String authorizationValue = accessToken(settings);
            Map<String, Object> current = user(settings, task.subject(), authorizationValue);
            Map<String, Object> updated = new LinkedHashMap<>(current);
            updated.put("enabled", true);
            updated.put("requiredActions", REQUIRED_ACTIONS);
            http.put().uri(settings.adminBaseUri() + "/users/{userId}",
                    safeIdentifier(task.subject()))
                .headers(headers -> headers.setBearerAuth(authorizationValue))
                .contentType(MediaType.APPLICATION_JSON).body(updated)
                .retrieve().toBodilessEntity();
            return null;
        });
    }

    @Override
    public void sendActionEmail(IdentityInvitationTask task) {
        KeycloakAdminSettings settings = validateTask(task);
        execute(() -> {
            http.put().uri(uriBuilder -> uriBuilder
                    .scheme(settings.adminBaseUri().getScheme())
                    .host(settings.adminBaseUri().getHost())
                    .port(settings.adminBaseUri().getPort())
                    .path(settings.adminBaseUri().getPath() + "/users/{userId}/execute-actions-email")
                    .queryParam("lifespan", actionLifespan.toSeconds())
                    .build(safeIdentifier(task.subject())))
                .headers(headers -> headers.setBearerAuth(accessToken(settings)))
                .contentType(MediaType.APPLICATION_JSON).body(REQUIRED_ACTIONS)
                .retrieve().toBodilessEntity();
            return null;
        });
    }

    private void createDisabled(KeycloakAdminSettings settings, UUID idempotencyKey,
                                String username, String email, String displayName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("email", email);
        body.put("firstName", displayName);
        body.put("enabled", false);
        body.put("emailVerified", false);
        body.put("requiredActions", REQUIRED_ACTIONS);
        body.put("attributes", Map.of(INVITATION_ATTRIBUTE, List.of(idempotencyKey.toString())));
        try {
            execute(() -> {
                http.post().uri(settings.adminBaseUri() + "/users")
                    .headers(headers -> headers.setBearerAuth(accessToken(settings)))
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
                return null;
            });
        } catch (IdentityProvisioningException exception) {
            if (!"KEYCLOAK_CONFLICT".equals(exception.errorCode())) {
                throw exception;
            }
        }
    }

    private UserRepresentation exactUsername(KeycloakAdminSettings settings, String username) {
        List<UserRepresentation> matches = execute(() -> users(settings, "username", username));
        if (matches.size() > 1) {
            throw new IdentityProvisioningException("KEYCLOAK_USERNAME_AMBIGUOUS");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private List<UserRepresentation> exactEmail(KeycloakAdminSettings settings, String email) {
        return execute(() -> users(settings, "email", email));
    }

    private List<UserRepresentation> users(KeycloakAdminSettings settings, String field, String value) {
        UserRepresentation[] result = http.get().uri(uriBuilder -> uriBuilder
                .scheme(settings.adminBaseUri().getScheme())
                .host(settings.adminBaseUri().getHost())
                .port(settings.adminBaseUri().getPort())
                .path(settings.adminBaseUri().getPath() + "/users")
                .queryParam(field, value).queryParam("exact", true).queryParam("max", 2).build())
            .headers(headers -> headers.setBearerAuth(accessToken(settings)))
            .retrieve().body(UserRepresentation[].class);
        if (result == null) {
            return List.of();
        }
        List<UserRepresentation> exact = new ArrayList<>();
        for (UserRepresentation user : result) {
            String candidate = "email".equals(field) ? user.email() : user.username();
            if (candidate != null && candidate.equalsIgnoreCase(value)) {
                exact.add(user);
            }
        }
        return List.copyOf(exact);
    }

    private static FederatedIdentity identity(KeycloakAdminSettings settings,
                                              UserRepresentation user,
                                              String username,
                                              FederatedIdentity.Mode mode) {
        return new FederatedIdentity(settings.issuer().toString(), safeIdentifier(user.id()),
            requireText(username, "KEYCLOAK_USERNAME_MISSING"), mode);
    }

    private static void requireReservedIdentity(UserRepresentation user, UUID idempotencyKey,
                                                String email) {
        List<String> marker = user.attributes() == null ? null
            : user.attributes().get(INVITATION_ATTRIBUTE);
        if (Boolean.TRUE.equals(user.enabled()) || user.email() == null
            || !user.email().equalsIgnoreCase(email) || marker == null || marker.size() != 1
            || !idempotencyKey.toString().equals(marker.getFirst())) {
            throw new IdentityProvisioningException("KEYCLOAK_INVITATION_IDENTITY_MISMATCH");
        }
    }

    private KeycloakAdminSettings validateTask(IdentityInvitationTask task) {
        if (task.mode() != FederatedIdentity.Mode.NEW_DISABLED) {
            throw new IdentityProvisioningException("KEYCLOAK_EXISTING_IDENTITY_ACTION_FORBIDDEN");
        }
        KeycloakAdminSettings settings = realms.require(task.accountDomain());
        if (!settings.issuer().toString().equals(task.issuer())) {
            throw new IdentityProvisioningException("KEYCLOAK_REALM_MISMATCH");
        }
        safeIdentifier(task.subject());
        return settings;
    }

    private Map<String, Object> user(KeycloakAdminSettings settings, String subject, String token) {
        Map<String, Object> result = http.get()
            .uri(settings.adminBaseUri() + "/users/{userId}", safeIdentifier(subject))
            .headers(headers -> headers.setBearerAuth(token))
            .retrieve().body(new ParameterizedTypeReference<>() { });
        if (result == null) {
            throw new IdentityProvisioningException("KEYCLOAK_EMPTY_USER");
        }
        return result;
    }

    private String accessToken(KeycloakAdminSettings settings) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        TokenResponse response = http.post().uri(settings.tokenUri())
            .headers(headers -> headers.setBasicAuth(settings.clientId(), settings.clientSecret()))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
            .retrieve().body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IdentityProvisioningException("KEYCLOAK_EMPTY_TOKEN");
        }
        return response.accessToken();
    }

    private static String safeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new IdentityProvisioningException("KEYCLOAK_IDENTIFIER_INVALID");
        }
        return value;
    }

    private static String requireText(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            throw new IdentityProvisioningException(errorCode);
        }
        return value;
    }

    private static <T> T execute(Action<T> action) {
        try {
            return action.run();
        } catch (IdentityProvisioningException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String code = status == 409 ? "KEYCLOAK_CONFLICT"
                : status >= 500 ? "KEYCLOAK_SERVER_FAILURE" : "KEYCLOAK_REQUEST_REJECTED";
            throw new IdentityProvisioningException(code, exception);
        } catch (RuntimeException exception) {
            throw new IdentityProvisioningException("KEYCLOAK_UNAVAILABLE", exception);
        }
    }

    record UserRepresentation(String id, String username, String email, Boolean enabled,
                              Map<String, List<String>> attributes) { }
    record TokenResponse(@com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken) { }

    @FunctionalInterface
    private interface Action<T> {
        T run();
    }
}
