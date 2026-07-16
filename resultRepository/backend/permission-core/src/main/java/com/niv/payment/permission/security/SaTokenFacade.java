package com.niv.payment.permission.security;

/**
 * Narrow boundary for Sa-Token. The backend integration module implements this
 * interface with StpUtil; the permission domain does not depend on framework statics.
 */
public interface SaTokenFacade {
    boolean isLoggedIn();

    Object sessionAttribute(String name);
}
