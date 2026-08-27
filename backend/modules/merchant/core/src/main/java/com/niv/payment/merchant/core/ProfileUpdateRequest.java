package com.niv.payment.merchant.core;

import java.util.List;
import java.util.UUID;

public record ProfileUpdateRequest(long expectedVersion, UUID idempotencyKey,
                                   String legalName, String displayName, String merchantTypeCode,
                                   String legalPersonName, String authenticationType, String remarks,
                                   List<String> marketCodes) { }
