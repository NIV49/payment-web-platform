package com.niv.payment.merchant.core;

public enum MerchantCommand {
    SUBMIT("merchant:submit"),
    RESUBMIT("merchant:resubmit"),
    APPROVE("merchant:review"),
    REJECT("merchant:review"),
    DISABLE("merchant:disable"),
    ENABLE("merchant:enable"),
    UPDATE_PROFILE("merchant:update"),
    CREATE("merchant:create"),
    AMEND("merchant:amend"),
    DOCUMENT_UPLOAD("merchant:document:upload"),
    DOCUMENT_VIEW("merchant:document:view"),
    TERMINATE("merchant:terminate");

    private final String permission;

    MerchantCommand(String permission) {
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }
}
