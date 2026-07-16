package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.persistence.mapper.MembershipMapper;
import com.niv.payment.permission.port.MembershipVersionRepository;

import java.util.Objects;

public final class MyBatisMembershipVersionRepository implements MembershipVersionRepository {
    private final MembershipMapper mapper;

    public MyBatisMembershipVersionRepository(MembershipMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public long findPermissionVersion(long tenantId, long membershipId) {
        Long version = mapper.findPermissionVersion(tenantId, membershipId);
        if (version == null) {
            throw new IllegalStateException("No active membership found for permission lookup");
        }
        return version;
    }
}
