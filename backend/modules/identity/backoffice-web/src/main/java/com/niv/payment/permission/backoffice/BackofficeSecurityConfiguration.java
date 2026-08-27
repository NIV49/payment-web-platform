package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.security.InvalidSessionException;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;

final class BackofficeSecurityConfiguration implements WebMvcConfigurer {
    private static final String REQUEST_PROOF_HEADER = "X-CSRF-Token";
    private static final String BACKCHANNEL_LOGOUT_PATH = "/api/auth/oidc/backchannel-logout";
    private static final Set<String> PUBLIC = Set.of(
        "POST /api/auth/login",
        "GET /api/auth/oidc/start",
        "GET /api/auth/oidc/callback",
        "POST /api/auth/oidc/handoff",
        "POST " + BACKCHANNEL_LOGOUT_PATH,
        "GET /api/health");

    private final SaTokenSessionBridge sessions;
    private final BackofficeAdministrationPermissionPolicy permissionPolicy;
    private final BackofficeAuthorizationEnforcer authorization;
    private final String allowedOrigin;

    BackofficeSecurityConfiguration(SaTokenSessionBridge sessions,
                                    BackofficeDeploymentProperties properties,
                                    BackofficeAdministrationPermissionPolicy permissionPolicy,
                                    BackofficeAuthorizationEnforcer authorization) {
        this.sessions = sessions;
        this.permissionPolicy = permissionPolicy;
        this.authorization = authorization;
        this.allowedOrigin = properties.allowedOrigin();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BoundaryInterceptor()).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigin)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Accept-Language", "X-Requested-With", REQUEST_PROOF_HEADER)
            .allowCredentials(true).maxAge(3600);
    }

    @Bean
    FilterRegistrationBean<jakarta.servlet.Filter> backofficeSecurityHeadersFilter() {
        FilterRegistrationBean<jakarta.servlet.Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter((request, response, chain) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            http.setHeader("X-Trace-Id", BackofficeRequestTrace.begin());
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("Referrer-Policy", "no-referrer");
            http.setHeader("Cache-Control", "no-store");
            try {
                chain.doFilter(request, response);
            } finally {
                BackofficeRequestTrace.end();
            }
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<jakarta.servlet.Filter> backofficeRequestBodySizeLimitFilter(
        tools.jackson.databind.ObjectMapper json,
        @org.springframework.beans.factory.annotation.Value("${payment.security.max-request-body-bytes:262144}")
        int maximumBytes,
        @org.springframework.beans.factory.annotation.Value(
            "${payment.security.merchant-document-upload-enabled:false}")
        boolean merchantDocumentUploadEnabled) {
        FilterRegistrationBean<jakarta.servlet.Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new BackofficeRequestBodySizeLimitFilter(
            json, maximumBytes, merchantDocumentUploadEnabled));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }

    final class BoundaryInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("OPTIONS".equals(request.getMethod())) {
                return true;
            }
            String route = request.getMethod() + " " + request.getRequestURI();
            boolean publicRoute = PUBLIC.contains(route);
            List<String> requiredPermissions = publicRoute
                ? List.of()
                : permissionPolicy.requiredPermissions(request.getMethod(), request.getRequestURI());
            if (isBackchannelLogout(request)) {
                return true;
            }
            if (!Set.of("GET", "HEAD").contains(request.getMethod())
                && !allowedOrigin.equals(request.getHeader("Origin"))) {
                throw new BackofficeAccessDeniedException();
            }
            if (!publicRoute) {
                var subject = sessions.currentSubject(request.getServerName());
                request.setAttribute(AuthorizationSubject.class.getName(), subject);
                if (!Set.of("GET", "HEAD").contains(request.getMethod())) {
                    try {
                        sessions.requireRequestProof(request.getHeader(REQUEST_PROOF_HEADER));
                    } catch (InvalidSessionException exception) {
                        throw new BackofficeAccessDeniedException();
                    }
                }
                requiredPermissions.forEach(permission ->
                    authorization.requireTenantPermission(subject, permission));
            }
            return true;
        }

        private boolean isBackchannelLogout(HttpServletRequest request) {
            return "POST".equals(request.getMethod())
                && BACKCHANNEL_LOGOUT_PATH.equals(request.getRequestURI());
        }
    }
}
