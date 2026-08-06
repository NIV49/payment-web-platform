package com.niv.payment.identity.lifecycle;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record MemberInvitationCommand(String email, String displayName, List<Long> roleIds,
                                      UUID idempotencyKey) {
    public MemberInvitationCommand {
        email = normalizeEmail(email);
        displayName = requireText(displayName, "displayName", 128);
        if (roleIds == null || roleIds.isEmpty() || roleIds.size() > 50
            || roleIds.stream().anyMatch(roleId -> roleId == null || roleId <= 0)
            || new HashSet<>(roleIds).size() != roleIds.size()) {
            throw new IllegalArgumentException("roleIds must contain unique positive identifiers");
        }
        roleIds = List.copyOf(roleIds);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }

    static String normalizeEmail(String value) {
        if (value == null) {
            throw new IllegalArgumentException("email is invalid");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('@');
        if (normalized.length() > 254 || separator <= 0 || separator != normalized.lastIndexOf('@')
            || separator == normalized.length() - 1 || normalized.chars().anyMatch(Character::isWhitespace)
            || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("email is invalid");
        }
        return normalized;
    }

    static String requireText(String value, String name, int maximumLength) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength
            || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
