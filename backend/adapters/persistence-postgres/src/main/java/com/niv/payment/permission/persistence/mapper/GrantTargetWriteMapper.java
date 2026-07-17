package com.niv.payment.permission.persistence.mapper;

import com.niv.payment.permission.persistence.entity.GrantTargetEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface GrantTargetWriteMapper {
    @Delete("DELETE FROM iam_grant_target WHERE dimension_id = #{dimensionId}")
    int deleteByDimensionId(@Param("dimensionId") long dimensionId);

    @Insert("""
        INSERT INTO iam_grant_target (id, dimension_id, target_ref, created_at)
        VALUES (#{id}, #{dimensionId}, #{targetRef}, CURRENT_TIMESTAMP)
        """)
    int insert(GrantTargetEntity entity);
}
