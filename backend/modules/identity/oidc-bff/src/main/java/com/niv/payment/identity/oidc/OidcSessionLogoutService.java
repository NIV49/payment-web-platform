package com.niv.payment.identity.oidc;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.permission.security.SessionAttributeNames;

import java.net.URI;
import java.util.Objects;

public final class OidcSessionLogoutService {
    private final StpLogic stpLogic;
    private final SpringOidcClient oidc;
    private final OidcClientSettings settings;

    public OidcSessionLogoutService(StpLogic stpLogic, SpringOidcClient oidc,
                                    OidcClientSettings settings) {
        this.stpLogic = Objects.requireNonNull(stpLogic, "stpLogic");
        this.oidc = Objects.requireNonNull(oidc, "oidc");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public URI logout() {
        SaSession session = stpLogic.getSession();
        Object issuer = session.get(SessionAttributeNames.ISSUER);
        Object idTokenValue = session.get(SessionAttributeNames.OIDC_ID_ASSERTION);
        try {
            if (!(issuer instanceof String value) || !settings.issuer().toString().equals(value)
                || !(idTokenValue instanceof String idToken) || idToken.isBlank()) {
                throw new OidcFlowService.LoginRejectedException();
            }
            return oidc.logoutUri(idToken);
        } finally {
            stpLogic.logout();
        }
    }
}
