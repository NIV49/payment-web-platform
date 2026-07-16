package com.niv.payment.permission.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MembershipMapper {
    @Select("""
        SELECT permission_version
          FROM iam_membership
         WHERE tenant_id = #{tenantId}
           AND id = #{membershipId}
           AND status = 'ACTIVE'
        """)
    Long findPermissionVersion(@Param("tenantId") long tenantId,
                               @Param("membershipId") long membershipId);

    @Select("""
        SELECT session_version
          FROM iam_membership
         WHERE tenant_id = #{tenantId}
           AND id = #{membershipId}
           AND status = 'ACTIVE'
        """)
    Long findSessionVersion(@Param("tenantId") long tenantId,
                            @Param("membershipId") long membershipId);

    @Update("""
        UPDATE iam_membership m
           SET permission_version = permission_version + 1,
               updated_at = CURRENT_TIMESTAMP,
               row_version = row_version + 1
         WHERE m.tenant_id = #{tenantId}
           AND EXISTS (
               SELECT 1
                 FROM iam_membership_role mr
                WHERE mr.membership_id = m.id
                  AND mr.tenant_id = m.tenant_id
                  AND mr.role_id = #{roleId}
           )
        """)
    int bumpPermissionVersionForRole(@Param("tenantId") long tenantId,
                                     @Param("roleId") long roleId);
}
