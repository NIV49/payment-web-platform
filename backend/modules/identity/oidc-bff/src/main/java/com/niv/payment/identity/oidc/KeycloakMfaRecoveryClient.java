package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MfaRecoveryTask;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class KeycloakMfaRecoveryClient implements MfaRecoveryRelay.KeycloakActions {
    private static final Set<String> MFA_CREDENTIAL_TYPES = Set.of(
        "otp", "webauthn", "webauthn-passwordless");
    private static final String RECOVERY_CODE_TYPE = "recovery-authn-codes";

    private final RestClient http;
    private final KeycloakAdminSettings settings;

    KeycloakMfaRecoveryClient(RestClient http, KeycloakAdminSettings settings) {
        this.http = Objects.requireNonNull(http, "http");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public void revokeMfaCredentials(MfaRecoveryTask task) {
        validateIdentity(task);
        credentials(task).stream()
            .filter(credential -> MFA_CREDENTIAL_TYPES.contains(credential.type()))
            .forEach(credential -> deleteCredential(task, credential.id()));
        requireTotpEnrollment(task);
    }

    @Override
    public void revokeRecoveryCodes(MfaRecoveryTask task) {
        validateIdentity(task);
        credentials(task).stream()
            .filter(credential -> RECOVERY_CODE_TYPE.equals(credential.type()))
            .forEach(credential -> deleteCredential(task, credential.id()));
    }

    @Override
    public void revokeKeycloakSessions(MfaRecoveryTask task) {
        validateIdentity(task);
        execute(() -> http.post()
            .uri(settings.adminBaseUri() + "/users/{userId}/logout", safeIdentifier(task.subject()))
            .headers(headers -> headers.setBearerAuth(accessToken()))
            .retrieve().toBodilessEntity());
    }

    private List<CredentialRepresentation> credentials(MfaRecoveryTask task) {
        return execute(() -> {
            CredentialRepresentation[] result = http.get()
                .uri(settings.adminBaseUri() + "/users/{userId}/credentials",
                    safeIdentifier(task.subject()))
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve().body(CredentialRepresentation[].class);
            return result == null ? List.of() : List.of(result);
        });
    }

    private void deleteCredential(MfaRecoveryTask task, String credentialId) {
        execute(() -> http.delete()
            .uri(settings.adminBaseUri() + "/users/{userId}/credentials/{credentialId}",
                safeIdentifier(task.subject()), safeIdentifier(credentialId))
            .headers(headers -> headers.setBearerAuth(accessToken()))
            .retrieve().toBodilessEntity());
    }

    private void requireTotpEnrollment(MfaRecoveryTask task) {
        execute(() -> {
            String bearerValue = accessToken();
            Map<String, Object> representation = http.get()
                .uri(settings.adminBaseUri() + "/users/{userId}", safeIdentifier(task.subject()))
                .headers(headers -> headers.setBearerAuth(bearerValue))
                .retrieve().body(new ParameterizedTypeReference<>() { });
            if (representation == null) {
                throw new MfaRecoveryActionException("KEYCLOAK_EMPTY_USER");
            }
            Map<String, Object> updated = new LinkedHashMap<>(representation);
            List<String> requiredActions = new ArrayList<>();
            Object current = representation.get("requiredActions");
            if (current instanceof List<?> values) {
                values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .forEach(requiredActions::add);
            }
            if (!requiredActions.contains("CONFIGURE_TOTP")) {
                requiredActions.add("CONFIGURE_TOTP");
            }
            updated.put("requiredActions", List.copyOf(requiredActions));
            http.put().uri(settings.adminBaseUri() + "/users/{userId}", safeIdentifier(task.subject()))
                .headers(headers -> headers.setBearerAuth(bearerValue))
                .contentType(MediaType.APPLICATION_JSON).body(updated)
                .retrieve().toBodilessEntity();
            return null;
        });
    }

    private String accessToken() {
        return execute(() -> {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            TokenResponse response = http.post().uri(settings.tokenUri())
                .headers(headers -> headers.setBasicAuth(settings.clientId(), settings.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new MfaRecoveryActionException("KEYCLOAK_EMPTY_TOKEN");
            }
            return response.accessToken();
        });
    }

    private void validateIdentity(MfaRecoveryTask task) {
        if (!settings.issuer().toString().equals(task.issuer())
            || !task.accountDomain().name().equals(settings.realm())) {
            throw new MfaRecoveryActionException("KEYCLOAK_REALM_MISMATCH");
        }
        safeIdentifier(task.subject());
    }

    private static String safeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new MfaRecoveryActionException("KEYCLOAK_IDENTIFIER_INVALID");
        }
        return value;
    }

    private static <T> T execute(Action<T> action) {
        try {
            return action.run();
        } catch (MfaRecoveryActionException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new MfaRecoveryActionException(status >= 500
                ? "KEYCLOAK_SERVER_FAILURE" : "KEYCLOAK_REQUEST_REJECTED", exception);
        } catch (RuntimeException exception) {
            throw new MfaRecoveryActionException("KEYCLOAK_UNAVAILABLE", exception);
        }
    }

    record CredentialRepresentation(String id, String type) { }
    record TokenResponse(@com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken) { }

    @FunctionalInterface
    private interface Action<T> {
        T run();
    }

    static final class MfaRecoveryActionException extends RuntimeException {
        private final String errorCode;

        MfaRecoveryActionException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        MfaRecoveryActionException(String errorCode, Throwable cause) {
            super(errorCode, cause);
            this.errorCode = errorCode;
        }

        String errorCode() {
            return errorCode;
        }
    }
}
