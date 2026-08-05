package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.security.InvalidSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Set;

final class BackofficeSecurityConfiguration implements WebMvcConfigurer {
    private static final String REQUEST_PROOF_HEADER = "X-CSRF-Token";
    private static final String BACKCHANNEL_LOGOUT_PATH = "/api/auth/oidc/backchannel-logout";
    private static final Map<String, Set<String>> ROUTES = Map.ofEntries(
        Map.entry("/api/auth/login", Set.of("POST")),
        Map.entry("/api/auth/logout", Set.of("POST")),
        Map.entry("/api/auth/csrf", Set.of("GET")),
        Map.entry("/api/auth/oidc/start", Set.of("GET")),
        Map.entry("/api/auth/oidc/callback", Set.of("GET")),
        Map.entry("/api/auth/oidc/handoff", Set.of("POST")),
        Map.entry("/api/auth/oidc/step-up/start", Set.of("POST")),
        Map.entry("/api/auth/oidc/step-up/handoff", Set.of("POST")),
        Map.entry("/api/identity/mfa-recoveries", Set.of("POST")),
        Map.entry(BACKCHANNEL_LOGOUT_PATH, Set.of("POST")),
        Map.entry("/api/user/info", Set.of("GET")),
        Map.entry("/api/auth/codes", Set.of("GET")),
        Map.entry("/api/menu/all", Set.of("GET")),
        Map.entry("/api/health", Set.of("GET")));
    private static final Set<String> PUBLIC = Set.of(
        "POST /api/auth/login",
        "GET /api/auth/oidc/start",
        "GET /api/auth/oidc/callback",
        "POST /api/auth/oidc/handoff",
        "POST " + BACKCHANNEL_LOGOUT_PATH,
        "GET /api/health");

    private final SaTokenSessionBridge sessions;
    private final String allowedOrigin;

    BackofficeSecurityConfiguration(SaTokenSessionBridge sessions,
                                    BackofficeDeploymentProperties properties) {
        this.sessions = sessions;
        this.allowedOrigin = properties.allowedOrigin();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BoundaryInterceptor()).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigin)
            .allowedMethods("GET", "POST", "OPTIONS")
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
        bean.setOrder(1);
        return bean;
    }

    @Bean
    FilterRegistrationBean<jakarta.servlet.Filter> backofficeRequestBodySizeLimitFilter(
        tools.jackson.databind.ObjectMapper json,
        @org.springframework.beans.factory.annotation.Value("${payment.security.max-request-body-bytes:262144}")
        int maximumBytes) {
        FilterRegistrationBean<jakarta.servlet.Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new BackofficeRequestBodySizeLimitFilter(json, maximumBytes));
        bean.setOrder(2);
        return bean;
    }

    final class BoundaryInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("OPTIONS".equals(request.getMethod())) {
                return true;
            }
            if (!ROUTES.getOrDefault(request.getRequestURI(), Set.of()).contains(request.getMethod())) {
                throw new BackofficeAccessDeniedException();
            }
            if (isBackchannelLogout(request)) {
                return true;
            }
            if (!Set.of("GET", "HEAD").contains(request.getMethod())
                && !allowedOrigin.equals(request.getHeader("Origin"))) {
                throw new BackofficeAccessDeniedException();
            }
            if (!PUBLIC.contains(request.getMethod() + " " + request.getRequestURI())) {
                sessions.currentSubject(request.getServerName());
                if (!Set.of("GET", "HEAD").contains(request.getMethod())) {
                    try {
                        sessions.requireRequestProof(request.getHeader(REQUEST_PROOF_HEADER));
                    } catch (InvalidSessionException exception) {
                        throw new BackofficeAccessDeniedException();
                    }
                }
            }
            return true;
        }

        private boolean isBackchannelLogout(HttpServletRequest request) {
            return "POST".equals(request.getMethod())
                && BACKCHANNEL_LOGOUT_PATH.equals(request.getRequestURI());
        }
    }
}
