package com.niv.payment.merchant.core.crypto;

import com.niv.payment.merchant.core.MerchantOnboardingModels.DocumentKind;

public interface MerchantOnboardingCryptography {
    ProtectedPayload protectLegalId(long merchantId, long tenantId, String legalIdType,
                                    String plaintext, String keyId);

    String decryptLegalId(long merchantId, long tenantId, String legalIdType,
                          ProtectedPayload payload);

    ProtectedPayload protectDocument(long documentId, long targetTenantId,
                                       long actorMembershipId, DocumentKind kind,
                                       String mediaType, int width, int height, int size,
                                       byte[] sanitizedContent, String keyId);

    byte[] decryptDocument(long documentId, long targetTenantId, long actorMembershipId,
                           DocumentKind kind, String mediaType, int width, int height, int size,
                           ProtectedPayload payload);
}
