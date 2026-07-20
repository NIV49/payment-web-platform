package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.persistence.mapper.MembershipMapper;
import com.niv.payment.permission.port.MembershipSessionVersionRepository;

import java.util.Objects;
import java.util.OptionalLong;

public final class MyBatisMembershipSessionVersionRepository implements MembershipSessionVersionRepository {
    private final MembershipMapper mapper;

    public MyBatisMembershipSessionVersionRepository(MembershipMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public OptionalLong findSessionVersion(long tenantId, long membershipId) {
        Long version = mapper.findSessionVersion(tenantId, membershipId);
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }
}
