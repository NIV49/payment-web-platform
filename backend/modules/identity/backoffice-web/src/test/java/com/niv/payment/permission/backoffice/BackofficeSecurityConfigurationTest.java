package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.security.InvalidSessionException;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BackofficeSecurityConfigurationTest {
    private static final String ORIGIN = "https://merchant.example.test";
    private final SaTokenSessionBridge sessions = mock(SaTokenSessionBridge.class);
    private final BackofficeAuthorizationEnforcer authorization =
        mock(BackofficeAuthorizationEnforcer.class);
    private final BackofficeSecurityConfiguration.BoundaryInterceptor interceptor =
        configuration().new BoundaryInterceptor();

    @Test
    void requestBodyIsBoundedBeforeFrameworkFormParsing() {
        BackofficeSecurityConfiguration configuration = configuration();

        assertThat(configuration.backofficeSecurityHeadersFilter().getOrder())
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(configuration.backofficeRequestBodySizeLimitFilter(
            new ObjectMapper(), 262_144, false).getOrder())
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Test
    void oidcCallbackDoesNotDependOnOrigin() {
        MockHttpServletRequest request = request("GET", "/api/auth/oidc/callback");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(sessions);
    }

    @Test
    void backChannelLogoutDoesNotDependOnOriginOrBrowserSession() {
        MockHttpServletRequest request = request("POST", "/api/auth/oidc/backchannel-logout");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(sessions);
    }

    @Test
    void browserHandoffRequiresTheTrustedOrigin() {
        MockHttpServletRequest missingOrigin = request("POST", "/api/auth/oidc/handoff");
        assertThatThrownBy(() -> interceptor.preHandle(
            missingOrigin, new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BackofficeAccessDeniedException.class);

        MockHttpServletRequest trusted = request("POST", "/api/auth/oidc/handoff");
        trusted.addHeader("Origin", ORIGIN);
        assertThat(interceptor.preHandle(trusted, new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(sessions);
    }

    @Test
    void cookieAuthenticatedLogoutRequiresAnIndependentRequestProof() {
        MockHttpServletRequest request = request("POST", "/api/auth/logout");
        request.addHeader("Origin", ORIGIN);
        AuthorizationSubject subject = mock(AuthorizationSubject.class);
        when(sessions.currentSubject("merchant.example.test")).thenReturn(subject);
        doThrow(new InvalidSessionException("invalid request proof"))
            .when(sessions).requireRequestProof(null);

        assertThatThrownBy(() -> interceptor.preHandle(
            request, new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        verify(sessions).currentSubject("merchant.example.test");
        verify(sessions).requireRequestProof(null);
    }

    @Test
    void stepUpStartAndHandoffRequireCurrentSessionOriginAndRequestProof() {
        AuthorizationSubject subject = mock(AuthorizationSubject.class);
        when(sessions.currentSubject("merchant.example.test")).thenReturn(subject);

        for (String path : Set.of(
            "/api/auth/oidc/step-up/start", "/api/auth/oidc/step-up/handoff")) {
            MockHttpServletRequest request = request("POST", path);
            request.addHeader("Origin", ORIGIN);
            request.addHeader("X-CSRF-Token", "request-proof");

            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }

        verify(sessions, org.mockito.Mockito.times(2)).currentSubject("merchant.example.test");
        verify(sessions, org.mockito.Mockito.times(2)).requireRequestProof("request-proof");
    }

    @Test
    void identityReadsRequireTheCurrentHostSessionWithoutBrowserWriteProof() {
        AuthorizationSubject subject = mock(AuthorizationSubject.class);
        when(sessions.currentSubject("merchant.example.test")).thenReturn(subject);

        for (String path : Set.of("/api/identity/members", "/api/identity/invitation-roles")) {
            MockHttpServletRequest request = request("GET", path);

            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }

        verify(sessions, org.mockito.Mockito.times(2)).currentSubject("merchant.example.test");
    }

    @Test
    void identityInvitationRequiresTrustedOriginAndIndependentRequestProof() {
        AuthorizationSubject subject = mock(AuthorizationSubject.class);
        when(sessions.currentSubject("merchant.example.test")).thenReturn(subject);
        MockHttpServletRequest request = request("POST", "/api/identity/invitations");
        request.addHeader("Origin", ORIGIN);
        request.addHeader("X-CSRF-Token", "request-proof");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

        verify(sessions).currentSubject("merchant.example.test");
        verify(sessions).requireRequestProof("request-proof");
    }

    @Test
    void userAdministrationRequiresSessionCsrfAndTheMappedPermission() {
        AuthorizationSubject subject = mock(AuthorizationSubject.class);
        when(sessions.currentSubject("merchant.example.test")).thenReturn(subject);
        MockHttpServletRequest request = request("POST", "/api/system/user");
        request.addHeader("Origin", ORIGIN);
        request.addHeader("X-CSRF-Token", "request-proof");

        assertThat(interceptor.preHandle(
            request, new MockHttpServletResponse(), new Object())).isTrue();

        assertThat(request.getAttribute(AuthorizationSubject.class.getName())).isSameAs(subject);
        verify(sessions).requireRequestProof("request-proof");
        verify(authorization).requireTenantPermission(subject, "user:create");
    }

    @Test
    void sharedMerchantBoundaryRejectsPlatformTenantBootstrap() {
        MockHttpServletRequest request = request("POST", "/api/identity/tenant-bootstraps");
        request.addHeader("Origin", ORIGIN);
        request.addHeader("X-CSRF-Token", "request-proof");

        assertThatThrownBy(() -> interceptor.preHandle(
            request, new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BackofficeAccessDeniedException.class);
        verifyNoInteractions(sessions);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServerName("merchant.example.test");
        return request;
    }

    private static BackofficeDeploymentProperties properties() {
        return new BackofficeDeploymentProperties(AccountDomain.MERCHANT, AccountDomain.MERCHANT.loginType(),
            ORIGIN, Set.of("/dashboard/workspace/index"));
    }

    private BackofficeSecurityConfiguration configuration() {
        return new BackofficeSecurityConfiguration(
            sessions, properties(),
            new BackofficeAdministrationPermissionPolicy(AccountDomain.MERCHANT), authorization);
    }
}
