package com.niv.payment.permission.persistence.mapper;

import com.niv.payment.permission.persistence.entity.GrantDimensionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface GrantDimensionWriteMapper {
    @Delete("DELETE FROM iam_grant_dimension WHERE grant_id = #{grantId}")
    int deleteByGrantId(@Param("grantId") long grantId);

    @Insert("""
        INSERT INTO iam_grant_dimension (id, grant_id, dimension_code, scope_mode, created_at)
        VALUES (#{id}, #{grantId}, #{dimensionCode}, #{scopeMode}, CURRENT_TIMESTAMP)
        """)
    int insert(GrantDimensionEntity entity);
}
