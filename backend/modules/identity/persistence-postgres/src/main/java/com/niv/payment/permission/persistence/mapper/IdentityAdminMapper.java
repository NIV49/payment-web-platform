package com.niv.payment.permission.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import com.niv.payment.permission.service.IdentityModels;

public interface IdentityAdminMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM iam_tenant WHERE id=#{tenantId} AND tenant_type='PLATFORM' AND status='ACTIVE')")
    boolean isActivePlatformTenant(@Param("tenantId") long tenantId);

    @Select("SELECT nextval('iam_id_seq')")
    long nextId();

    @Select("""
        SELECT u.id AS "userId", m.id AS "membershipId", m.tenant_id AS "tenantId",
               m.department_id AS "departmentId", m.permission_version AS "permissionVersion",
               m.session_version AS "sessionVersion", c.password_hash AS "passwordHash"
          FROM iam_authentication_credential c
          JOIN iam_user u ON u.id = c.user_id AND u.status = 'ACTIVE'
          JOIN iam_membership m ON m.user_id = u.id AND m.status = 'ACTIVE'
          JOIN iam_tenant t ON t.id = m.tenant_id AND t.status = 'ACTIVE'
         WHERE c.username = #{username} AND c.status = 'ACTIVE' AND c.password_hash IS NOT NULL
           AND (NOT #{tenantSelected} OR m.tenant_id = #{tenantId})
           AND (
               #{tenantSelected}
               OR 1 = (
                   SELECT count(*)
                     FROM iam_membership candidate
                     JOIN iam_tenant candidate_tenant ON candidate_tenant.id = candidate.tenant_id
                                                    AND candidate_tenant.status = 'ACTIVE'
                    WHERE candidate.user_id = u.id AND candidate.status = 'ACTIVE'
               )
           )
         ORDER BY m.id LIMIT 1
        """)
    Map<String, Object> findActiveCredential(@Param("username") String username,
                                             @Param("tenantId") long tenantId,
                                             @Param("tenantSelected") boolean tenantSelected);

    @Update("""
        UPDATE iam_authentication_credential SET last_login_at = now(), updated_at = now(),
               row_version = row_version + 1 WHERE user_id = #{userId}
        """)
    int markLoginSucceeded(@Param("userId") long userId);

    @Update("""
        UPDATE iam_authentication_credential SET password_hash=#{passwordHash},updated_at=now(),row_version=row_version+1
         WHERE username='admin' AND password_hash IS NULL
        """)
    int initializeBootstrapPassword(@Param("passwordHash") String passwordHash);

    @Select("""
        SELECT u.id AS "id", c.username AS "username", u.display_name AS "realName",
               '' AS "avatar", COALESCE(string_agg(DISTINCT r.role_code, ','), '') AS "roles",
               '/dashboard' AS "homePath"
          FROM iam_membership m JOIN iam_user u ON u.id = m.user_id
          JOIN iam_authentication_credential c ON c.user_id = u.id
          LEFT JOIN iam_membership_role mr ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.id
          LEFT JOIN iam_role r ON r.tenant_id = m.tenant_id AND r.id = mr.role_id AND r.status = 'ACTIVE'
         WHERE m.tenant_id = #{tenantId} AND m.id = #{membershipId} AND m.status = 'ACTIVE'
         GROUP BY u.id, c.username, u.display_name
        """)
    Map<String, Object> findCurrentUser(@Param("tenantId") long tenantId,
                                        @Param("membershipId") long membershipId);

    @Select("""
        SELECT DISTINCT p.permission_code
          FROM iam_membership_role mr
          JOIN iam_role r ON r.tenant_id = mr.tenant_id AND r.id = mr.role_id AND r.status = 'ACTIVE'
          JOIN iam_role_grant rg ON rg.tenant_id = r.tenant_id AND rg.role_id = r.id AND rg.status = 'ACTIVE'
          JOIN iam_permission p ON p.id = rg.permission_id AND p.status = 'ACTIVE'
          JOIN iam_grant_dimension gd ON gd.grant_id = rg.id
             AND gd.dimension_code = 'TENANT' AND gd.scope_mode = 'TENANT_ALL'
         WHERE mr.tenant_id = #{tenantId} AND mr.membership_id = #{membershipId}
           AND p.required_dimensions = ARRAY['TENANT']::VARCHAR(32)[]
           AND (rg.valid_from IS NULL OR rg.valid_from <= now())
           AND (rg.valid_until IS NULL OR rg.valid_until > now())
         ORDER BY p.permission_code
        """)
    List<String> findPermissionCodes(@Param("tenantId") long tenantId,
                                     @Param("membershipId") long membershipId);

    @Select("""
        SELECT DISTINCT m.id AS "id", m.parent_id AS "parentId", m.menu_type AS "type",
               COALESCE(m.route_name, m.menu_name) AS "name", m.route_path AS "path",
               m.component_path AS "component", m.redirect_path AS "redirect", m.auth_code AS "authCode",
               m.meta_json::text AS "metaJson", CASE WHEN m.status = 'ACTIVE' THEN 1 ELSE 0 END AS "status",
               m.sort_order AS "sortOrder"
          FROM iam_role_menu rm
          JOIN iam_membership_role mr ON mr.tenant_id = rm.tenant_id AND mr.role_id = rm.role_id
          JOIN iam_role r ON r.tenant_id = rm.tenant_id AND r.id = rm.role_id AND r.status = 'ACTIVE'
          JOIN iam_menu m ON m.tenant_id = rm.tenant_id AND m.id = rm.menu_id AND m.status = 'ACTIVE'
         WHERE rm.tenant_id = #{tenantId} AND mr.membership_id = #{membershipId}
         ORDER BY "sortOrder", "id"
        """)
    List<Map<String, Object>> findAccessibleMenus(@Param("tenantId") long tenantId,
                                                   @Param("membershipId") long membershipId);

    @Select("""
        <script>
        SELECT u.id AS "id", m.id AS "membershipId", c.username AS "username",
               u.display_name AS "name", m.department_id AS "departmentId",
               d.department_name AS "departmentName",
               COALESCE(string_agg(DISTINCT r.id::text, ','), '') AS "roleIds",
               COALESCE(string_agg(DISTINCT r.role_name, ','), '') AS "roleNames",
               CASE WHEN m.status = 'ACTIVE' THEN 1 ELSE 0 END AS "status",
               m.row_version AS "userVersion", u.remark AS "remark", m.created_at AS "createdAt"
          FROM iam_membership m JOIN iam_user u ON u.id = m.user_id
          JOIN iam_authentication_credential c ON c.user_id = u.id
          LEFT JOIN iam_department d ON d.tenant_id = m.tenant_id AND d.id = m.department_id
          LEFT JOIN iam_membership_role mr ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.id
          LEFT JOIN iam_role r ON r.tenant_id = m.tenant_id AND r.id = mr.role_id
         WHERE m.tenant_id = #{tenantId} AND m.status != 'TERMINATED'
         <if test="query.username != null and query.username != ''">AND lower(c.username) LIKE '%' || lower(#{query.username}) || '%'</if>
         <if test="query.name != null and query.name != ''">AND lower(u.display_name) LIKE '%' || lower(#{query.name}) || '%'</if>
         <if test="query.id != null">AND u.id=#{query.id}</if>
         <if test="query.status != null">AND m.status=CASE WHEN #{query.status}=1 THEN 'ACTIVE' ELSE 'DISABLED' END</if>
         <if test="query.departmentId != null">AND m.department_id=#{query.departmentId}</if>
         <if test="query.startTime != null">AND m.created_at &gt;= #{query.startTime}</if>
         <if test="query.endTime != null">AND m.created_at &lt;= #{query.endTime}</if>
         GROUP BY u.id, m.id, c.username, u.display_name, d.department_name, u.remark
         ORDER BY m.created_at DESC, m.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> findUsers(@Param("tenantId") long tenantId,
                                        @Param("query") IdentityModels.UserQuery query,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    @Select("""
        <script>SELECT count(*) FROM iam_membership m JOIN iam_user u ON u.id=m.user_id
        JOIN iam_authentication_credential c ON c.user_id=u.id
        WHERE m.tenant_id=#{tenantId} AND m.status != 'TERMINATED'
        <if test="query.username != null and query.username != ''">AND lower(c.username) LIKE '%' || lower(#{query.username}) || '%'</if>
        <if test="query.name != null and query.name != ''">AND lower(u.display_name) LIKE '%' || lower(#{query.name}) || '%'</if>
        <if test="query.id != null">AND u.id=#{query.id}</if>
        <if test="query.status != null">AND m.status=CASE WHEN #{query.status}=1 THEN 'ACTIVE' ELSE 'DISABLED' END</if>
        <if test="query.departmentId != null">AND m.department_id=#{query.departmentId}</if>
        <if test="query.startTime != null">AND m.created_at &gt;= #{query.startTime}</if>
        <if test="query.endTime != null">AND m.created_at &lt;= #{query.endTime}</if>
        </script>
        """)
    long countUsers(@Param("tenantId") long tenantId, @Param("query") IdentityModels.UserQuery query);

    @Select("""
        SELECT mr.role_id FROM iam_membership m JOIN iam_membership_role mr
          ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
         WHERE m.tenant_id=#{tenantId} AND m.user_id=#{userId} ORDER BY mr.role_id
        """)
    List<Long> findUserRoleIds(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Select("""
        <script>
        SELECT r.id AS "id", r.role_name AS "name",
               COALESCE(string_agg(DISTINCT rm.menu_id::text, ','), '') AS "menuIds",
               CASE WHEN r.status='ACTIVE' THEN 1 ELSE 0 END AS "status", r.remark AS "remark",
               r.created_at AS "createdAt"
          FROM iam_role r LEFT JOIN iam_role_menu rm ON rm.tenant_id=r.tenant_id AND rm.role_id=r.id
         WHERE r.tenant_id=#{tenantId}
         <if test="query.name != null and query.name != ''">AND lower(r.role_name) LIKE '%' || lower(#{query.name}) || '%'</if>
         <if test="query.id != null">AND r.id=#{query.id}</if>
         <if test="query.status != null">AND r.status = CASE WHEN #{query.status}=1 THEN 'ACTIVE' ELSE 'DISABLED' END</if>
         <if test="query.remark != null and query.remark != ''">AND lower(COALESCE(r.remark,'')) LIKE '%' || lower(#{query.remark}) || '%'</if>
         <if test="query.startTime != null">AND r.created_at &gt;= #{query.startTime}</if>
         <if test="query.endTime != null">AND r.created_at &lt;= #{query.endTime}</if>
         GROUP BY r.id ORDER BY r.created_at DESC, r.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Map<String, Object>> findRoles(@Param("tenantId") long tenantId,
                                        @Param("query") IdentityModels.RoleQuery query,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    @Select("""
        <script>SELECT count(*) FROM iam_role WHERE tenant_id=#{tenantId}
        <if test="query.name != null and query.name != ''">AND lower(role_name) LIKE '%' || lower(#{query.name}) || '%'</if>
        <if test="query.id != null">AND id=#{query.id}</if>
        <if test="query.status != null">AND status = CASE WHEN #{query.status}=1 THEN 'ACTIVE' ELSE 'DISABLED' END</if>
        <if test="query.remark != null and query.remark != ''">AND lower(COALESCE(remark,'')) LIKE '%' || lower(#{query.remark}) || '%'</if>
        <if test="query.startTime != null">AND created_at &gt;= #{query.startTime}</if>
        <if test="query.endTime != null">AND created_at &lt;= #{query.endTime}</if>
        </script>
        """)
    long countRoles(@Param("tenantId") long tenantId, @Param("query") IdentityModels.RoleQuery query);

    @Select("""
        SELECT id AS "id", parent_id AS "parentId", department_name AS "name",
               CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END AS "status", remark AS "remark",
               created_at AS "createdAt" FROM iam_department WHERE tenant_id=#{tenantId}
               ORDER BY created_at, id
        """)
    List<Map<String, Object>> findDepartments(@Param("tenantId") long tenantId);

    @Select("""
        SELECT id AS "id", parent_id AS "parentId", menu_type AS "type",
               COALESCE(route_name, menu_name) AS "name", route_path AS "path",
               component_path AS "component", redirect_path AS "redirect", auth_code AS "authCode",
               meta_json::text AS "metaJson", CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END AS "status",
               sort_order AS "sortOrder" FROM iam_menu WHERE tenant_id=#{tenantId} ORDER BY sort_order,id
        """)
    List<Map<String, Object>> findMenus(@Param("tenantId") long tenantId);

    @Select("SELECT id FROM iam_membership WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND status!='TERMINATED'")
    Long findMembershipId(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Select("""
        <script>SELECT count(*) FROM iam_role WHERE tenant_id=#{tenantId} AND status='ACTIVE'
        AND id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
        """)
    int countActiveRoles(@Param("tenantId") long tenantId, @Param("ids") List<Long> ids);
    @Select("SELECT id FROM iam_tenant WHERE id=#{tenantId} FOR UPDATE")
    Long lockTenantForAdministration(@Param("tenantId") long tenantId);

    @Select("""
        SELECT id, assignable, system_role AS "systemRole"
          FROM iam_role
         WHERE tenant_id=#{tenantId}
        """)
    List<RoleAssignmentRow> findRoleAssignmentFacts(@Param("tenantId") long tenantId);

    @Select("""
        SELECT role_id
          FROM iam_membership_role
         WHERE tenant_id=#{tenantId} AND membership_id=#{membershipId}
         ORDER BY role_id
        """)
    List<Long> findMembershipRoleIds(@Param("tenantId") long tenantId,
                                     @Param("membershipId") long membershipId);

    @Select("""
        SELECT EXISTS(
            SELECT 1
              FROM iam_membership_role mr
              JOIN iam_role r ON r.tenant_id=mr.tenant_id AND r.id=mr.role_id
             WHERE mr.tenant_id=#{tenantId} AND mr.membership_id=#{membershipId}
               AND r.system_role=true AND r.status='ACTIVE'
        )
        """)
    boolean membershipHasActiveSystemRole(@Param("tenantId") long tenantId,
                                          @Param("membershipId") long membershipId);

    @Select("""
        SELECT EXISTS(
            SELECT 1
              FROM iam_membership m
              JOIN iam_membership_role mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
              JOIN iam_role r ON r.tenant_id=mr.tenant_id AND r.id=mr.role_id
             WHERE m.tenant_id=#{tenantId} AND m.id != #{membershipId} AND m.status='ACTIVE'
               AND r.system_role=true AND r.status='ACTIVE'
        )
        """)
    boolean hasOtherActiveSystemAdministrator(@Param("tenantId") long tenantId,
                                              @Param("membershipId") long membershipId);


    @Insert("""
        INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark)
        VALUES(#{id},'local',#{username},#{name},#{status},#{remark})
        """)
    int insertUser(@Param("id") long id, @Param("username") String username, @Param("name") String name,
                   @Param("status") String status, @Param("remark") String remark);

    @Insert("""
        INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status)
        SELECT #{id},#{tenantId},#{userId},#{departmentId},#{status}
        WHERE EXISTS(SELECT 1 FROM iam_department WHERE tenant_id=#{tenantId} AND id=#{departmentId})
        """)
    int insertMembership(@Param("id") long id, @Param("tenantId") long tenantId,
                         @Param("userId") long userId, @Param("departmentId") long departmentId,
                         @Param("status") String status);

    @Insert("INSERT INTO iam_authentication_credential(user_id,username,status) VALUES(#{userId},#{username},#{status})")
    int insertCredential(@Param("userId") long userId, @Param("username") String username,
                         @Param("status") String status);

    @Update("""
        UPDATE iam_membership SET department_id=#{departmentId}, status=#{status},
               permission_version=permission_version+1, session_version=session_version+1,
               row_version=row_version+1, updated_at=now()
         WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND row_version=#{version}
           AND status!='TERMINATED'
           AND EXISTS(SELECT 1 FROM iam_department WHERE tenant_id=#{tenantId} AND id=#{departmentId})
        """)
    int updateMembership(@Param("tenantId") long tenantId, @Param("userId") long userId,
                         @Param("departmentId") long departmentId, @Param("status") String status,
                         @Param("version") long version);

    @Update("""
        UPDATE iam_user SET display_name=#{name}, status=#{status}, remark=#{remark}, updated_at=now(),
               row_version=row_version+1 WHERE id=#{userId}
        """)
    int updateUser(@Param("userId") long userId, @Param("name") String name,
                   @Param("status") String status, @Param("remark") String remark);

    @Update("UPDATE iam_authentication_credential SET username=#{username}, status=#{status}, updated_at=now(), row_version=row_version+1 WHERE user_id=#{userId}")
    int updateCredential(@Param("userId") long userId, @Param("username") String username,
                         @Param("status") String status);

    @Update("UPDATE iam_authentication_credential SET status=#{status},updated_at=now(),row_version=row_version+1 WHERE user_id=#{userId}")
    int updateCredentialStatus(@Param("userId") long userId, @Param("status") String status);

    @Update("UPDATE iam_user SET status=#{status},updated_at=now(),row_version=row_version+1 WHERE id=#{userId}")
    int updateUserStatus(@Param("userId") long userId, @Param("status") String status);

    @Update("""
        UPDATE iam_membership SET status=#{status}, permission_version=permission_version+1,
               session_version=session_version+1, row_version=row_version+1, updated_at=now()
         WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND row_version=#{version} AND status!='TERMINATED'
        """)
    int updateMembershipStatus(@Param("tenantId") long tenantId, @Param("userId") long userId,
                               @Param("status") String status, @Param("version") long version);

    @Select("SELECT row_version FROM iam_membership WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    Long findUserVersion(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Update("""
        UPDATE iam_membership SET status='TERMINATED', permission_version=permission_version+1,
               session_version=session_version+1,row_version=row_version+1,updated_at=now()
         WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND status!='TERMINATED'
        """)
    int terminateMembership(@Param("tenantId") long tenantId, @Param("userId") long userId);

    @Delete("DELETE FROM iam_membership_role WHERE tenant_id=#{tenantId} AND membership_id=#{membershipId}")
    int deleteMembershipRoles(@Param("tenantId") long tenantId, @Param("membershipId") long membershipId);

    @Insert("""
        <script>INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by) VALUES
        <foreach collection="roleIds" item="roleId" separator=",">
          (#{tenantId},#{membershipId},#{roleId},#{operatorId})
        </foreach></script>
        """)
    int insertMembershipRoles(@Param("tenantId") long tenantId, @Param("membershipId") long membershipId,
                              @Param("roleIds") List<Long> roleIds, @Param("operatorId") long operatorId);

    @Insert("""
        INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,assignable,system_role,status,remark)
        VALUES(#{id},#{tenantId},#{code},#{name},'PLATFORM',true,false,#{status},#{remark})
        """)
    int insertRole(@Param("id") long id, @Param("tenantId") long tenantId, @Param("code") String code,
                   @Param("name") String name, @Param("status") String status, @Param("remark") String remark);

    @Update("UPDATE iam_role SET role_name=#{name},status=#{status},remark=#{remark},updated_at=now(),row_version=row_version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND system_role=false")
    int updateRole(@Param("tenantId") long tenantId, @Param("id") long id, @Param("name") String name,
                   @Param("status") String status, @Param("remark") String remark);

    @Update("UPDATE iam_role SET status=#{status},updated_at=now(),row_version=row_version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND system_role=false")
    int updateRoleStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("status") String status);

    @Update("UPDATE iam_role SET status='DISABLED',updated_at=now(),row_version=row_version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND system_role=false")
    int disableRole(@Param("tenantId") long tenantId, @Param("id") long id);

    @Delete("DELETE FROM iam_role_menu WHERE tenant_id=#{tenantId} AND role_id=#{roleId}")
    int deleteRoleMenus(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    @Insert("""
        <script>INSERT INTO iam_role_menu(tenant_id,role_id,menu_id) VALUES
        <foreach collection="menuIds" item="menuId" separator=",">(#{tenantId},#{roleId},#{menuId})</foreach>
        </script>
        """)
    int insertRoleMenus(@Param("tenantId") long tenantId, @Param("roleId") long roleId,
                        @Param("menuIds") List<Long> menuIds);

    @Update("""
        UPDATE iam_membership m SET permission_version=permission_version+1,updated_at=now()
         WHERE tenant_id=#{tenantId} AND EXISTS(SELECT 1 FROM iam_membership_role mr
          WHERE mr.tenant_id=m.tenant_id AND mr.membership_id=m.id AND mr.role_id=#{roleId})
        """)
    int bumpRoleMembers(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    @Insert("""
        INSERT INTO iam_department(id,tenant_id,parent_id,department_code,department_name,status,remark)
        VALUES(#{id},#{tenantId},#{parentId},#{code},#{name},#{status},#{remark})
        """)
    int insertDepartment(@Param("id") long id, @Param("tenantId") long tenantId,
                         @Param("parentId") Long parentId, @Param("code") String code,
                         @Param("name") String name, @Param("status") String status,
                         @Param("remark") String remark);

    @Update("UPDATE iam_department SET parent_id=#{parentId},department_name=#{name},status=#{status},remark=#{remark},updated_at=now(),row_version=row_version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND id != COALESCE(#{parentId},-1)")
    int updateDepartment(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("parentId") Long parentId, @Param("name") String name,
                         @Param("status") String status, @Param("remark") String remark);

    @Update("UPDATE iam_department SET status='DISABLED',updated_at=now(),row_version=row_version+1 WHERE tenant_id=#{tenantId} AND id=#{id}")
    int disableDepartment(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select("""
        WITH RECURSIVE descendants AS (
          SELECT id FROM iam_department WHERE tenant_id=#{tenantId} AND id=#{id}
          UNION ALL SELECT d.id FROM iam_department d JOIN descendants x ON d.parent_id=x.id
           WHERE d.tenant_id=#{tenantId}
        )
        SELECT #{parentId} IS NULL OR (EXISTS(SELECT 1 FROM iam_department WHERE tenant_id=#{tenantId} AND id=#{parentId})
          AND NOT EXISTS(SELECT 1 FROM descendants WHERE id=#{parentId}))
        """)
    boolean departmentParentAllowed(@Param("tenantId") long tenantId, @Param("id") long id,
                                    @Param("parentId") Long parentId);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM iam_department WHERE tenant_id=#{tenantId} AND parent_id=#{id} AND status='ACTIVE')
            OR EXISTS(SELECT 1 FROM iam_membership WHERE tenant_id=#{tenantId} AND department_id=#{id} AND status='ACTIVE')
        """)
    boolean departmentHasDependents(@Param("tenantId") long tenantId, @Param("id") long id);

    @Insert("""
        INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,component_path,
                             redirect_path,auth_code,meta_json,status,sort_order)
        VALUES(#{id},#{tenantId},#{parentId},#{type},#{name},#{name},#{path},#{component},#{redirect},#{authCode},
               CAST(#{metaJson} AS jsonb),#{status},999)
        """)
    int insertMenu(@Param("id") long id, @Param("tenantId") long tenantId, @Param("parentId") Long parentId,
                   @Param("type") String type, @Param("name") String name, @Param("path") String path,
                   @Param("component") String component, @Param("redirect") String redirect,
                   @Param("authCode") String authCode, @Param("metaJson") String metaJson,
                   @Param("status") String status);

    @Update("""
        UPDATE iam_menu SET parent_id=#{parentId},menu_type=#{type},menu_name=#{name},route_name=#{name},
               route_path=#{path},component_path=#{component},redirect_path=#{redirect},auth_code=#{authCode},
               meta_json=CAST(#{metaJson} AS jsonb),status=#{status},updated_at=now(),row_version=row_version+1
         WHERE tenant_id=#{tenantId} AND id=#{id} AND id != COALESCE(#{parentId},-1)
        """)
    int updateMenu(@Param("tenantId") long tenantId, @Param("id") long id, @Param("parentId") Long parentId,
                   @Param("type") String type, @Param("name") String name, @Param("path") String path,
                   @Param("component") String component, @Param("redirect") String redirect,
                   @Param("authCode") String authCode, @Param("metaJson") String metaJson,
                   @Param("status") String status);

    @Update("UPDATE iam_menu SET status='DISABLED',updated_at=now(),row_version=row_version+1 WHERE tenant_id=#{tenantId} AND id=#{id}")
    int disableMenu(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select("""
        WITH RECURSIVE descendants AS (
          SELECT id FROM iam_menu WHERE tenant_id=#{tenantId} AND id=#{id}
          UNION ALL SELECT m.id FROM iam_menu m JOIN descendants x ON m.parent_id=x.id WHERE m.tenant_id=#{tenantId}
        )
        SELECT #{parentId} IS NULL OR (EXISTS(SELECT 1 FROM iam_menu WHERE tenant_id=#{tenantId} AND id=#{parentId})
          AND NOT EXISTS(SELECT 1 FROM descendants WHERE id=#{parentId}))
        """)
    boolean menuParentAllowed(@Param("tenantId") long tenantId, @Param("id") long id,
                              @Param("parentId") Long parentId);

    @Select("SELECT EXISTS(SELECT 1 FROM iam_menu WHERE tenant_id=#{tenantId} AND parent_id=#{id} AND status='ACTIVE')")
    boolean menuHasActiveChildren(@Param("tenantId") long tenantId, @Param("id") long id);

    @Select("SELECT EXISTS(SELECT 1 FROM iam_menu WHERE tenant_id=#{tenantId} AND lower(COALESCE(route_name,menu_name))=lower(#{value}) AND (#{excludedId} IS NULL OR id != #{excludedId}))")
    boolean menuNameExists(@Param("tenantId") long tenantId, @Param("value") String value,
                           @Param("excludedId") Long excludedId);

    @Select("SELECT EXISTS(SELECT 1 FROM iam_menu WHERE tenant_id=#{tenantId} AND route_path=#{value} AND (#{excludedId} IS NULL OR id != #{excludedId}))")
    boolean menuPathExists(@Param("tenantId") long tenantId, @Param("value") String value,
                           @Param("excludedId") Long excludedId);

    @Insert("""
        INSERT INTO iam_audit_event(id,tenant_id,operator_membership_id,target_type,target_ref,action_code,
                                    decision,reason_code,permission_code,after_value,trace_id)
        VALUES(nextval('iam_id_seq'),#{tenantId},#{operatorId},#{targetType},#{targetRef},#{action},
               'ALLOW','AUTHORIZED',#{permissionCode},'{}'::jsonb,#{traceId})
        """)
    int insertAudit(@Param("tenantId") long tenantId, @Param("operatorId") long operatorId,
                    @Param("targetType") String targetType, @Param("targetRef") String targetRef,
                    @Param("action") String action, @Param("permissionCode") String permissionCode,
                    @Param("traceId") String traceId);
}
