package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.security.SaTokenSessionBridge;
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
    private static final Map<String, Set<String>> ROUTES = Map.of(
        "/api/auth/login", Set.of("POST"),
        "/api/auth/logout", Set.of("POST"),
        "/api/user/info", Set.of("GET"),
        "/api/auth/codes", Set.of("GET"),
        "/api/menu/all", Set.of("GET"),
        "/api/health", Set.of("GET"));
    private static final Set<String> PUBLIC = Set.of("POST /api/auth/login", "GET /api/health");

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
            .allowedHeaders("Content-Type", "Accept-Language", "X-Requested-With")
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

    private final class BoundaryInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("OPTIONS".equals(request.getMethod())) {
                return true;
            }
            if (!ROUTES.getOrDefault(request.getRequestURI(), Set.of()).contains(request.getMethod())) {
                throw new BackofficeAccessDeniedException();
            }
            if (!Set.of("GET", "HEAD").contains(request.getMethod())
                && !allowedOrigin.equals(request.getHeader("Origin"))) {
                throw new BackofficeAccessDeniedException();
            }
            if (!PUBLIC.contains(request.getMethod() + " " + request.getRequestURI())) {
                sessions.currentSubject();
            }
            return true;
        }
    }
}
