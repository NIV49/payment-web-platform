package com.niv.payment.identity.oidc;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.security.SessionAttributeNames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class OidcStepUpSession implements OidcStepUpFlowService.SessionStepUp {
    private final AccountDomain accountDomain;
    private final StpLogic stpLogic;

    public OidcStepUpSession(AccountDomain accountDomain, StpLogic stpLogic) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.stpLogic = Objects.requireNonNull(stpLogic, "stpLogic");
    }

    @Override
    public OidcStepUpFlowService.StepUpPrincipal current(String requestHost) {
        if (!stpLogic.isLogin()) {
            throw new OidcFlowService.LoginRejectedException();
        }
        SaSession session = stpLogic.getSession();
        AccountDomain sessionDomain;
        try {
            sessionDomain = AccountDomain.valueOf(requiredString(session, SessionAttributeNames.ACCOUNT_DOMAIN));
        } catch (IllegalArgumentException exception) {
            throw new OidcFlowService.LoginRejectedException(exception);
        }
        String host = requiredString(session, SessionAttributeNames.ENTRY_HOST);
        if (sessionDomain != accountDomain
            || !host.equals(requestHost.trim().toLowerCase(Locale.ROOT))) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return new OidcStepUpFlowService.StepUpPrincipal(
            sessionDomain,
            requiredLong(session, SessionAttributeNames.TENANT_ID),
            requiredLong(session, SessionAttributeNames.USER_ID),
            requiredLong(session, SessionAttributeNames.MEMBERSHIP_ID),
            host,
            requiredString(session, SessionAttributeNames.ISSUER),
            requiredString(session, SessionAttributeNames.SUBJECT),
            sha256(requiredTokenValue()));
    }

    @Override
    public void complete(OidcStepUpFlowService.StepUpPrincipal expected,
                         OidcFlowService.AuthenticatedIdentity identity,
                         Instant completedAt) {
        OidcStepUpFlowService.StepUpPrincipal current = current(expected.entryHost());
        if (!expected.equals(current)
            || !expected.issuer().equals(identity.issuer())
            || !expected.subject().equals(identity.subject())) {
            throw new OidcFlowService.LoginRejectedException();
        }
        SaSession session = stpLogic.getSession();
        session.set(SessionAttributeNames.STEP_UP_AT, completedAt.getEpochSecond());
        session.set(SessionAttributeNames.STEP_UP_VERIFIED, true);
    }

    private String requiredTokenValue() {
        String value = stpLogic.getTokenValue();
        if (value == null || value.isBlank()) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return value;
    }

    private static long requiredLong(SaSession session, String name) {
        Object value = session.get(name);
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return number.longValue();
    }

    private static String requiredString(SaSession session, String name) {
        Object value = session.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new OidcFlowService.LoginRejectedException();
        }
        return text;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
