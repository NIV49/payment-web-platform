package com.niv.payment.permission.persistence.mapper;

import com.niv.payment.permission.persistence.entity.RoleGrantEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface RoleGrantWriteMapper {
    @Update("""
        UPDATE iam_role_grant
           SET status = 'DISABLED',
               updated_by = #{operatorMembershipId},
               updated_at = CURRENT_TIMESTAMP,
               row_version = row_version + 1
         WHERE tenant_id = #{tenantId}
           AND role_id = #{roleId}
           AND status = 'ACTIVE'
        """)
    int disableActiveByRole(@Param("tenantId") long tenantId,
                            @Param("roleId") long roleId,
                            @Param("operatorMembershipId") long operatorMembershipId);

    @Select(value = """
        INSERT INTO iam_role_grant (
            id, tenant_id, role_id, permission_id, grant_key, status,
            valid_from, valid_until, created_by, created_at, updated_by, updated_at, row_version
        ) VALUES (
            #{id}, #{tenantId}, #{roleId}, #{permissionId}, #{grantKey}, #{status},
            #{validFrom}, #{validUntil}, #{createdBy}, CURRENT_TIMESTAMP,
            #{updatedBy}, CURRENT_TIMESTAMP, #{rowVersion}
        )
        ON CONFLICT (role_id, permission_id, grant_key) DO UPDATE
           SET status = EXCLUDED.status,
               valid_from = EXCLUDED.valid_from,
               valid_until = EXCLUDED.valid_until,
               updated_by = EXCLUDED.updated_by,
               updated_at = CURRENT_TIMESTAMP,
               row_version = iam_role_grant.row_version + 1
         WHERE iam_role_grant.tenant_id = EXCLUDED.tenant_id
        RETURNING id
        """, affectData = true)
    long upsertReturningId(RoleGrantEntity entity);
}
