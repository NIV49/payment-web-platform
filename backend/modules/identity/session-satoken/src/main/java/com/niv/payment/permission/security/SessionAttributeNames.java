package com.niv.payment.permission.security;

public final class SessionAttributeNames {
    public static final String USER_ID = "userId";
    public static final String ACCOUNT_DOMAIN = "accountDomain";
    public static final String MEMBERSHIP_ID = "membershipId";
    public static final String TENANT_ID = "tenantId";
    public static final String DEPARTMENT_ID = "departmentId";
    public static final String PERMISSION_VERSION = "permissionVersion";
    public static final String SESSION_VERSION = "sessionVersion";
    public static final String IDENTITY_VERSION = "identityVersion";
    public static final String ENTRY_HOST = "entryHost";
    public static final String ISSUER = "issuer";
    public static final String SUBJECT = "subject";
    public static final String OIDC_SESSION_ID = "oidcSessionId";
    public static final String AUTH_TIME = "authTime";
    public static final String ACR = "acr";
    public static final String OIDC_ID_ASSERTION = "oidcIdToken";
    public static final String STEP_UP_AT = "stepUpAt";
    public static final String STEP_UP_VERIFIED = "stepUpVerified";
    public static final String REQUEST_PROOF = "requestProof";

    private SessionAttributeNames() {
    }
}
