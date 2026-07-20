package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import com.niv.payment.adminapi.web.RequestTrace;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class SecurityConfiguration implements WebMvcConfigurer {
    private final SaTokenSessionBridge sessions;
    private final AdminAuthorizationEnforcer authorization;
    private final AdminApiPermissionPolicy permissionPolicy;
    private final Set<String> allowedOrigins;

    public SecurityConfiguration(SaTokenSessionBridge sessions, AdminAuthorizationEnforcer authorization,
                                 AdminApiPermissionPolicy permissionPolicy,
                                 @Value("${payment.security.allowed-origins}") String origins) {
        this.sessions = sessions;
        this.authorization = authorization;
        this.permissionPolicy = permissionPolicy;
        this.allowedOrigins = new LinkedHashSet<>(Arrays.stream(origins.split(",")).map(String::trim).toList());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminSecurityInterceptor()).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Accept-Language", "X-Requested-With")
            .allowCredentials(true).maxAge(3600);
    }

    @Bean
    FilterRegistrationBean<jakarta.servlet.Filter> securityHeadersFilter() {
        FilterRegistrationBean<jakarta.servlet.Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter((request, response, chain) -> {
            HttpServletResponse http = (HttpServletResponse) response;
            String traceId = RequestTrace.begin();
            http.setHeader("X-Trace-Id", traceId);
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("Referrer-Policy", "no-referrer");
            http.setHeader("Cache-Control", "no-store");
            try {
                chain.doFilter(request, response);
            } finally {
                RequestTrace.end();
            }
        });
        bean.setOrder(1);
        return bean;
    }

    private final class AdminSecurityInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("OPTIONS".equals(request.getMethod())) return true;
            requireTrustedOriginForMutation(request);
            if (permissionPolicy.isPublic(request.getMethod(), request.getRequestURI())) return true;

            AuthorizationSubject subject = sessions.currentSubject();
            List<String> required = permissionPolicy.requiredPermissions(request.getMethod(), request.getRequestURI());
            for (String permission : required) {
                authorization.requireTenantPermission(subject, permission);
            }
            request.setAttribute(AuthorizationSubject.class.getName(), subject);
            return true;
        }

        private void requireTrustedOriginForMutation(HttpServletRequest request) {
            if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return;
            String origin = request.getHeader("Origin");
            if (origin == null || !allowedOrigins.contains(origin)) throw new AccessDeniedException();
        }

    }
}
