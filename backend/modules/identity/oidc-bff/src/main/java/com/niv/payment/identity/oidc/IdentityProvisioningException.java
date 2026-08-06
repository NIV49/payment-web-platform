package com.niv.payment.identity.oidc;

final class IdentityProvisioningException extends RuntimeException {
    private final String errorCode;

    IdentityProvisioningException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    IdentityProvisioningException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    String errorCode() {
        return errorCode;
    }
}
