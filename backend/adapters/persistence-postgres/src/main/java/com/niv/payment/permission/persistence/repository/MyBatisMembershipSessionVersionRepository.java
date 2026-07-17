package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.persistence.mapper.MembershipMapper;
import com.niv.payment.permission.port.MembershipSessionVersionRepository;

import java.util.Objects;

public final class MyBatisMembershipSessionVersionRepository implements MembershipSessionVersionRepository {
    private final MembershipMapper mapper;

    public MyBatisMembershipSessionVersionRepository(MembershipMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public long findSessionVersion(long tenantId, long membershipId) {
        Long version = mapper.findSessionVersion(tenantId, membershipId);
        if (version == null) {
            throw new IllegalStateException("No active membership found for session validation");
        }
        return version;
    }
}
