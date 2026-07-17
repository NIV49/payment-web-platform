package com.niv.payment.permission;

import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.RiskLevel;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.persistence.mapper.PermissionDefinitionRow;
import com.niv.payment.permission.persistence.repository.MyBatisPermissionCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBatisPermissionCatalogRepositoryTest {

    @Test
    void mapsTrustedPermissionRiskAndRequiredDimensions() {
        var repository = new MyBatisPermissionCatalogRepository(code -> Optional.of(
            new PermissionDefinitionRow(code, "FUND", "MERCHANT,MARKET", true, true)));

        var definition = repository.require(PermissionCode.of("payout:approve"));

        assertEquals(RiskLevel.FUND, definition.riskLevel());
        assertTrue(definition.requiredDimensions().contains(ScopeDimension.MERCHANT));
        assertTrue(definition.requiresStepUp());
    }
}
