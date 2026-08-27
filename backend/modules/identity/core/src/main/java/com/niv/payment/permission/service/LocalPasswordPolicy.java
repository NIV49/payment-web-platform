package com.niv.payment.permission.service;

/** Exact local-only administrator password-reset contract shared by every backoffice. */
public final class LocalPasswordPolicy {
    public static final int MIN_LENGTH = 16;
    public static final int MAX_LENGTH = 24;
    public static final int REQUIRED_SPECIAL_CHARACTERS = 4;
    public static final String SPECIAL_CHARACTERS = "!@#$%^&*";

    private LocalPasswordPolicy() {
    }

    public static String requireValid(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw invalid();
        }
        boolean uppercase = false;
        boolean lowercase = false;
        boolean digit = false;
        int specialCharacters = 0;
        for (int index = 0; index < password.length(); index++) {
            char character = password.charAt(index);
            if (character >= 'A' && character <= 'Z') {
                uppercase = true;
            } else if (character >= 'a' && character <= 'z') {
                lowercase = true;
            } else if (character >= '0' && character <= '9') {
                digit = true;
            } else if (SPECIAL_CHARACTERS.indexOf(character) >= 0) {
                specialCharacters++;
            } else {
                throw invalid();
            }
        }
        if (!uppercase || !lowercase || !digit
            || specialCharacters != REQUIRED_SPECIAL_CHARACTERS) {
            throw invalid();
        }
        return password;
    }

    private static IdentityAdministrationService.InvalidCommandException invalid() {
        return new IdentityAdministrationService.InvalidCommandException(
            "Password does not match the local password policy");
    }
}
