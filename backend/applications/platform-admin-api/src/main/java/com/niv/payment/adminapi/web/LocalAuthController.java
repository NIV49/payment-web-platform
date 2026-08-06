package com.niv.payment.adminapi.web;

import com.niv.payment.permission.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "payment.identity", name = "local-login-enabled", havingValue = "true")
final class LocalAuthController {
    private final AuthenticationService authentication;

    LocalAuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        authentication.login(new AuthenticationService.LoginCommand(
            request.username(), request.password(), http.getRemoteAddr()));
        return ApiResponse.success(new LoginResponse("cookie-session"));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout() {
        authentication.logout();
        return ApiResponse.success(null);
    }

    record LoginRequest(@NotBlank @Size(max = 100) String username,
                        @NotBlank @Size(max = 256) String password) { }
    record LoginResponse(String accessToken) { }
}
