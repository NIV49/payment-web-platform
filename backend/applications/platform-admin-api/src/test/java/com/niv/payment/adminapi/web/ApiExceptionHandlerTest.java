package com.niv.payment.adminapi.web;

import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.StalePermissionVersionException;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiExceptionHandlerTest {

    @Test
    void invalidAuthorizationSubjectClearsSessionAndReturns401() {
        AuthenticationService authentication = mock(AuthenticationService.class);
        var handler = new ApiExceptionHandler(authentication);

        var response = handler.staleSession(new InvalidAuthorizationSubjectException());

        verify(authentication).logout();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().error()).isEqualTo("SESSION_INVALID");
    }

    @Test
    void repeatedPermissionVersionRaceClearsSessionAndReturns401() {
        AuthenticationService authentication = mock(AuthenticationService.class);
        var handler = new ApiExceptionHandler(authentication);

        var response = handler.stalePermissionVersion(new StalePermissionVersionException());

        verify(authentication).logout();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().error()).isEqualTo("SESSION_INVALID");
    }

    @Test
    void optimisticLockConflictHasAStableMachineReadableError() {
        var handler = new ApiExceptionHandler(mock(AuthenticationService.class));

        var response = handler.optimisticLockConflict(
            new IdentityAdministrationService.OptimisticLockException());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo(40902);
        assertThat(response.getBody().error()).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("The record has changed; reload and retry");
    }

    @Test
    void databaseIntegrityConflictKeepsTheGenericDataConflictCode() {
        var handler = new ApiExceptionHandler(mock(AuthenticationService.class));

        var response = handler.dataConflict(new DataIntegrityViolationException("duplicate"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo(40901);
        assertThat(response.getBody().error()).isEqualTo("DATA_CONFLICT");
    }

    @Test
    void legacyAdministrationCutoverHasAStableFailClosedConflict() {
        var handler = new ApiExceptionHandler(mock(AuthenticationService.class));

        var response = handler.legacyAdministrationCutoverRequired();

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo(40903);
        assertThat(response.getBody().error()).isEqualTo("LEGACY_ADMINISTRATION_CUTOVER_REQUIRED");
    }

    @Test
    void multipartFailuresUseBoundedBadRequestAndPayloadTooLargeEnvelopes() {
        var handler = new ApiExceptionHandler(mock(AuthenticationService.class));

        var malformed = handler.malformedMultipart(
            new org.springframework.web.multipart.MultipartException("parser internals"));
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(malformed.getBody().code()).isEqualTo(40001);
        assertThat(malformed.getBody().error()).isEqualTo("INVALID_REQUEST");
        assertThat(malformed.getBody().message()).isEqualTo("Invalid request");

        var oversized = handler.multipartTooLarge(
            new org.springframework.web.multipart.MaxUploadSizeExceededException(2_097_152));
        assertThat(oversized.getStatusCode().value()).isEqualTo(413);
        assertThat(oversized.getBody().code()).isEqualTo(41301);
        assertThat(oversized.getBody().error()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(oversized.getBody().message()).isEqualTo("Request body is too large");

        var missingPart = handler.badRequest(
            new org.springframework.web.multipart.support.MissingServletRequestPartException("file"));
        assertThat(missingPart.getStatusCode().value()).isEqualTo(400);
        assertThat(missingPart.getBody().error()).isEqualTo("INVALID_REQUEST");
    }
}
