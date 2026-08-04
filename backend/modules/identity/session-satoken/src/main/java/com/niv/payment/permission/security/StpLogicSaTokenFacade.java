package com.niv.payment.permission.security;

import cn.dev33.satoken.stp.StpLogic;

import java.util.Objects;

public final class StpLogicSaTokenFacade implements SaTokenFacade {
    private final StpLogic stpLogic;

    public StpLogicSaTokenFacade(StpLogic stpLogic) {
        this.stpLogic = Objects.requireNonNull(stpLogic, "stpLogic");
    }

    @Override
    public boolean isLoggedIn() {
        return stpLogic.isLogin();
    }

    @Override
    public Object sessionAttribute(String name) {
        return stpLogic.getSession().get(name);
    }
}
