package com.niv.payment.permission.persistence.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@FunctionalInterface
public interface PermissionGrantMapper {
    @Select("""
        WITH eligible_grant AS (
            SELECT rg.id AS grant_id,
                   rg.role_id,
                   rg.valid_from,
                   rg.valid_until,
                   p.permission_code,
                   p.risk_level,
                   p.cross_tenant_mode,
                   array_to_string(p.required_dimensions, ',') AS required_dimensions,
                   p.requires_step_up,
                   p.requires_approval
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
             WHERE m.tenant_id = #{tenantId}
               AND m.id = #{membershipId}
               AND m.status = 'ACTIVE'
        ), active_grant AS (
            SELECT *
              FROM eligible_grant
             WHERE (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
               AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
        ), grant_rows AS (
            SELECT ag.*,
                   gd.id AS dimension_id,
                   gd.dimension_code,
                   gd.scope_mode,
                   gt.target_ref
              FROM active_grant ag
              LEFT JOIN iam_grant_dimension gd ON gd.grant_id = ag.grant_id
              LEFT JOIN iam_grant_target gt ON gt.dimension_id = gd.id
        ), boundary AS (
            SELECT min(boundary_at) AS refresh_after
              FROM (
                    SELECT valid_from AS boundary_at FROM eligible_grant
                     WHERE valid_from > CURRENT_TIMESTAMP
                    UNION ALL
                    SELECT valid_until AS boundary_at FROM eligible_grant
                     WHERE valid_until > CURRENT_TIMESTAMP
                   ) temporal_boundary
        )
        SELECT gr.grant_id,
               gr.role_id,
               gr.permission_code,
               gr.risk_level,
               gr.cross_tenant_mode,
               gr.required_dimensions,
               gr.requires_step_up,
               gr.requires_approval,
               gr.dimension_id,
               gr.dimension_code,
               gr.scope_mode,
               gr.target_ref,
               b.refresh_after
          FROM boundary b
          LEFT JOIN grant_rows gr ON TRUE
         ORDER BY gr.grant_id NULLS LAST, gr.dimension_id NULLS LAST, gr.target_ref
        """)
    @ConstructorArgs({
        @Arg(column = "grant_id", javaType = Long.class),
        @Arg(column = "role_id", javaType = Long.class),
        @Arg(column = "permission_code", javaType = String.class),
        @Arg(column = "risk_level", javaType = String.class),
        @Arg(column = "cross_tenant_mode", javaType = String.class),
        @Arg(column = "required_dimensions", javaType = String.class),
        @Arg(column = "requires_step_up", javaType = Boolean.class),
        @Arg(column = "requires_approval", javaType = Boolean.class),
        @Arg(column = "dimension_id", javaType = Long.class),
        @Arg(column = "dimension_code", javaType = String.class),
        @Arg(column = "scope_mode", javaType = String.class),
        @Arg(column = "target_ref", javaType = String.class),
        @Arg(column = "refresh_after", javaType = OffsetDateTime.class)
    })
    List<GrantRecordRow> findActiveGrantRows(@Param("tenantId") long tenantId,
                                             @Param("membershipId") long membershipId);
}
