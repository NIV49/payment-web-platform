package com.niv.payment.permission.security;

import cn.dev33.satoken.stp.StpUtil;

public final class StpUtilSaTokenFacade implements SaTokenFacade {
    @Override
    public boolean isLoggedIn() {
        return StpUtil.isLogin();
    }

    @Override
    public Object sessionAttribute(String name) {
        return StpUtil.getSession().get(name);
    }
}
