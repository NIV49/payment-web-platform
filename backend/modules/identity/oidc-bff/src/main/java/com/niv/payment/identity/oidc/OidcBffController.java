package com.niv.payment.identity.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/auth")
final class OidcBffController {
    private final OidcFlowService flow;
    private final OidcStepUpFlowService stepUp;
    private final OidcSessionLogoutService logout;
    private final OidcBackChannelLogoutService backChannelLogout;
    private final OidcRequestTrace trace;

    OidcBffController(OidcFlowService flow, OidcStepUpFlowService stepUp,
                      OidcSessionLogoutService logout,
                      OidcBackChannelLogoutService backChannelLogout, OidcRequestTrace trace) {
        this.flow = flow;
        this.stepUp = stepUp;
        this.logout = logout;
        this.backChannelLogout = backChannelLogout;
        this.trace = trace;
    }

    @GetMapping("/oidc/start")
    ResponseEntity<Void> start(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(flow.start(request.getServerName()).authorizationUri()).build();
    }

    @GetMapping("/oidc/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error) {
        if (error != null) {
            if (OidcStepUpFlowService.isStepUpState(state)) {
                stepUp.rejectCallback(state);
            }
            flow.rejectCallback(state);
        }
        if (OidcStepUpFlowService.isStepUpState(state)) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(stepUp.callback(code, state).redirectUri()).build();
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(flow.callback(code, state).redirectUri()).build();
    }

    @PostMapping("/oidc/handoff")
    OidcApiResponse<MarkerResponse> handoff(@Valid @RequestBody HandoffRequest body,
                                            HttpServletRequest request) {
        OidcFlowService.LoginResult result = flow.redeem(body.handoff(), request.getServerName());
        return OidcApiResponse.success(new MarkerResponse(result.marker()), trace.current());
    }

    @PostMapping("/oidc/step-up/start")
    OidcApiResponse<StepUpStartResponse> startStepUp(HttpServletRequest request) {
        OidcStepUpFlowService.StartResult result = stepUp.start(request.getServerName());
        return OidcApiResponse.success(
            new StepUpStartResponse(result.authorizationUri().toString()), trace.current());
    }

    @PostMapping("/oidc/step-up/handoff")
    OidcApiResponse<StepUpResponse> stepUpHandoff(@Valid @RequestBody StepUpHandoffRequest body,
                                                 HttpServletRequest request) {
        OidcStepUpFlowService.StepUpResult result = stepUp.redeem(
            body.handoff(), request.getServerName());
        return OidcApiResponse.success(new StepUpResponse(result.stepUpAt().toString()), trace.current());
    }

    @PostMapping("/logout")
    OidcApiResponse<LogoutResponse> logout() {
        return OidcApiResponse.success(new LogoutResponse(logout.logout().toString()), trace.current());
    }

    @PostMapping(value = "/oidc/backchannel-logout",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> backChannelLogout(@RequestParam("logout_token") String signedLogout) {
        backChannelLogout.logout(signedLogout);
        return ResponseEntity.noContent().build();
    }

    record HandoffRequest(@NotBlank @Size(max = 512) String handoff) { }
    record StepUpHandoffRequest(@NotBlank @Size(max = 512) String handoff) { }
    record MarkerResponse(String accessToken) { }
    record StepUpStartResponse(String redirectUrl) { }
    record StepUpResponse(String stepUpAt) { }
    record LogoutResponse(String logoutUrl) { }
    record OidcApiResponse<T>(int code, T data, String error, String message, String traceId) {
        static <T> OidcApiResponse<T> success(T data, String traceId) {
            return new OidcApiResponse<>(0, data, null, "success", traceId);
        }
    }
}
