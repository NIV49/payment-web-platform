package com.niv.payment.adminapi.web;

import com.niv.payment.permission.security.SaTokenSessionBridge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
final class SessionSecurityController {
    private final SaTokenSessionBridge sessions;

    SessionSecurityController(SaTokenSessionBridge sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/csrf")
    ApiResponse<RequestProofResponse> requestProof() {
        return ApiResponse.success(new RequestProofResponse(sessions.requestProof()));
    }

    record RequestProofResponse(String requestProof) { }
}
