package com.niv.payment.permission;

import com.niv.payment.permission.persistence.mapper.GrantRecordRow;
import com.niv.payment.permission.persistence.mapper.PermissionGrantMapper;
import com.niv.payment.permission.persistence.repository.MyBatisPermissionGrantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MyBatisPermissionGrantRepositoryTest {

    @Test
    void groupsJoinedRowsIntoOneAtomicGrant() {
        PermissionGrantMapper mapper = (tenantId, membershipId) -> List.of(
            row(11L, "MERCHANT", "SPECIFIED", "M1"),
            row(11L, "MARKET", "SPECIFIED", "PK"));

        var repository = new MyBatisPermissionGrantRepository(mapper);
        var snapshot = repository.load(3L, 2L, 7L);

        assertEquals(1, snapshot.grants().size());
        assertEquals(2, snapshot.grants().get(0).scopes().size());
        assertEquals(7L, snapshot.permissionVersion());
    }

    private static GrantRecordRow row(long grantId, String dimension, String mode, String target) {
        return new GrantRecordRow(grantId, 21L, "payout:approve", "FUND", "MERCHANT,MARKET",
            true, true, 31L + dimension.hashCode(), dimension, mode, target);
    }
}
