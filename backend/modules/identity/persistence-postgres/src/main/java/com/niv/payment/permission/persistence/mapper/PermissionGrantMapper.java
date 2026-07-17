package com.niv.payment.permission.persistence.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@FunctionalInterface
public interface PermissionGrantMapper {
    @Select("""
        SELECT rg.id AS grant_id,
               rg.role_id AS role_id,
               p.permission_code AS permission_code,
               p.risk_level AS risk_level,
               array_to_string(p.required_dimensions, ',') AS required_dimensions,
               p.requires_step_up AS requires_step_up,
               p.requires_approval AS requires_approval,
               gd.id AS dimension_id,
               gd.dimension_code AS dimension_code,
               gd.scope_mode AS scope_mode,
               gt.target_ref AS target_ref
          FROM iam_membership m
          JOIN iam_membership_role mr ON mr.tenant_id = m.tenant_id
                                     AND mr.membership_id = m.id
          JOIN iam_role r ON r.id = mr.role_id
                         AND r.tenant_id = m.tenant_id
                         AND r.status = 'ACTIVE'
          JOIN iam_role_grant rg ON rg.role_id = r.id
                                AND rg.tenant_id = m.tenant_id
                                AND rg.status = 'ACTIVE'
          JOIN iam_permission p ON p.id = rg.permission_id
                               AND p.status = 'ACTIVE'
          LEFT JOIN iam_grant_dimension gd ON gd.grant_id = rg.id
          LEFT JOIN iam_grant_target gt ON gt.dimension_id = gd.id
         WHERE m.tenant_id = #{tenantId}
           AND m.id = #{membershipId}
           AND m.status = 'ACTIVE'
           AND (rg.valid_from IS NULL OR rg.valid_from <= CURRENT_TIMESTAMP)
           AND (rg.valid_until IS NULL OR rg.valid_until > CURRENT_TIMESTAMP)
         ORDER BY rg.id, gd.id, gt.target_ref
        """)
    @ConstructorArgs({
        @Arg(column = "grant_id", javaType = long.class),
        @Arg(column = "role_id", javaType = long.class),
        @Arg(column = "permission_code", javaType = String.class),
        @Arg(column = "risk_level", javaType = String.class),
        @Arg(column = "required_dimensions", javaType = String.class),
        @Arg(column = "requires_step_up", javaType = boolean.class),
        @Arg(column = "requires_approval", javaType = boolean.class),
        @Arg(column = "dimension_id", javaType = Long.class),
        @Arg(column = "dimension_code", javaType = String.class),
        @Arg(column = "scope_mode", javaType = String.class),
        @Arg(column = "target_ref", javaType = String.class)
    })
    List<GrantRecordRow> findActiveGrantRows(@Param("tenantId") long tenantId,
                                             @Param("membershipId") long membershipId);
}
