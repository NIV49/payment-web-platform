package com.niv.payment.merchant.web;

import com.niv.payment.merchant.core.MerchantOnboardingModels.LegalIdValidity;
import com.niv.payment.merchant.core.MerchantOnboardingModels.Profile;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveMode;
import com.niv.payment.merchant.core.MerchantOnboardingModels.SensitiveValue;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

final class MerchantOnboardingJson {
    private static final Set<String> PROFILE_FIELDS = Set.of(
        "displayName","brandName","authenticationType","merchantTypeCode","industryCode",
        "brandLogoDocumentId","legalName","registrationCountry","marketCodes",
        "registeredAddress","operatingAddress","businessLicenseDocumentId","legalPersonName",
        "contactEmail","contactPhone","legalIdTypeCode","legalIdNo","legalIdValidity",
        "legalIdFrontDocumentId","legalIdBackDocumentId","legalIdHoldingDocumentId","remarks",
        "registrationNumber");
    private static final Set<String> VALIDITY_FIELDS = Set.of("validFrom","validTo");

    private MerchantOnboardingJson() { }

    static Profile profile(JsonNode node) {
        MerchantJson.exactRequiredObject(node, PROFILE_FIELDS);
        return new Profile(
            MerchantJson.requiredString(node, "displayName"),
            MerchantJson.requiredString(node, "brandName"),
            MerchantJson.requiredString(node, "authenticationType"),
            MerchantJson.requiredString(node, "merchantTypeCode"),
            MerchantJson.requiredString(node, "industryCode"),
            MerchantJson.positiveLongString(node, "brandLogoDocumentId"),
            MerchantJson.requiredString(node, "legalName"),
            MerchantJson.requiredString(node, "registrationCountry"),
            MerchantJson.requiredStringList(node, "marketCodes"),
            MerchantJson.requiredString(node, "registeredAddress"),
            MerchantJson.requiredString(node, "operatingAddress"),
            MerchantJson.positiveLongString(node, "businessLicenseDocumentId"),
            MerchantJson.requiredString(node, "legalPersonName"),
            MerchantJson.requiredString(node, "contactEmail"),
            MerchantJson.requiredString(node, "contactPhone"),
            MerchantJson.requiredString(node, "legalIdTypeCode"),
            sensitive(node.get("legalIdNo")), validity(node.get("legalIdValidity")),
            MerchantJson.positiveLongString(node, "legalIdFrontDocumentId"),
            MerchantJson.positiveLongString(node, "legalIdBackDocumentId"),
            MerchantJson.positiveLongString(node, "legalIdHoldingDocumentId"),
            MerchantJson.requiredString(node, "remarks"), sensitive(node.get("registrationNumber")));
    }

    private static SensitiveValue sensitive(JsonNode node) {
        if (node == null || !node.isObject() || node.get("mode") == null
            || !node.get("mode").isString()) throw MerchantJson.invalid();
        String mode = node.get("mode").stringValue();
        if ("RETAIN".equals(mode)) {
            MerchantJson.exactRequiredObject(node, Set.of("mode"));
            return new SensitiveValue(SensitiveMode.RETAIN, null);
        }
        if (!"REPLACE".equals(mode)) throw MerchantJson.invalid();
        MerchantJson.exactRequiredObject(node, Set.of("mode","value"));
        return new SensitiveValue(SensitiveMode.REPLACE, MerchantJson.requiredString(node, "value"));
    }

    private static LegalIdValidity validity(JsonNode node) {
        MerchantJson.exactRequiredObject(node, VALIDITY_FIELDS);
        try {
            return new LegalIdValidity(LocalDate.parse(MerchantJson.requiredString(node, "validFrom")),
                LocalDate.parse(MerchantJson.requiredString(node, "validTo")));
        } catch (DateTimeParseException exception) {
            throw MerchantJson.invalid();
        }
    }
}
