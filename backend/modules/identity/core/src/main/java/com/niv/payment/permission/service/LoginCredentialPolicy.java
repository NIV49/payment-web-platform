package com.niv.payment.permission.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single trust rule for deciding whether a stored local credential is safe to hand to BCrypt.
 * Costs below the baseline are too weak; costs above the operational ceiling can turn login or
 * administrator checks into a CPU denial of service.
 */
public final class LoginCredentialPolicy {
    public static final int MIN_BCRYPT_COST = 10;
    public static final int MAX_BCRYPT_COST = 14;

    private static final Pattern BCRYPT = Pattern.compile(
        "\\A\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}\\z");

    private LoginCredentialPolicy() {
    }

    public static boolean isLoginCapableHash(String passwordHash) {
        if (passwordHash == null) {
            return false;
        }
        Matcher matcher = BCRYPT.matcher(passwordHash);
        if (!matcher.matches()) {
            return false;
        }
        int cost = Integer.parseInt(matcher.group(1));
        return cost >= MIN_BCRYPT_COST && cost <= MAX_BCRYPT_COST;
    }
}
