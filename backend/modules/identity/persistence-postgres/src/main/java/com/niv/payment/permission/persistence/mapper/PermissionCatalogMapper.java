package com.niv.payment.permission.persistence.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@FunctionalInterface
public interface PermissionCatalogMapper {
    @Select("""
        SELECT permission_code,
               risk_level,
               cross_tenant_mode,
               array_to_string(required_dimensions, ',') AS required_dimensions,
               requires_step_up,
               requires_approval
          FROM iam_permission
         WHERE permission_code = #{permissionCode}
           AND status = 'ACTIVE'
        """)
    @ConstructorArgs({
        @Arg(column = "permission_code", javaType = String.class),
        @Arg(column = "risk_level", javaType = String.class),
        @Arg(column = "cross_tenant_mode", javaType = String.class),
        @Arg(column = "required_dimensions", javaType = String.class),
        @Arg(column = "requires_step_up", javaType = boolean.class),
        @Arg(column = "requires_approval", javaType = boolean.class)
    })
    Optional<PermissionDefinitionRow> findActiveByCode(@Param("permissionCode") String permissionCode);
}
