package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.security.InvalidSessionException;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    private final BackofficeSecurityConfiguration.BoundaryInterceptor interceptor =
        new BackofficeSecurityConfiguration(sessions, properties()).new BoundaryInterceptor();

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

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServerName("merchant.example.test");
        return request;
    }

    private static BackofficeDeploymentProperties properties() {
        return new BackofficeDeploymentProperties(AccountDomain.MERCHANT, AccountDomain.MERCHANT.loginType(),
            ORIGIN, Set.of("/dashboard/workspace/index"));
    }
}
