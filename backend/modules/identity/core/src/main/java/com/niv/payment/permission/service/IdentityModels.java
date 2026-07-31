package com.niv.payment.permission.service;

import java.time.Instant;
import java.util.List;

public final class IdentityModels {
    private IdentityModels() {
    }

    public record Page<T>(List<T> items, long total) {
        public Page { items = List.copyOf(items); }
    }

    public record CurrentUser(long id, String username, String realName, String avatar,
                              List<String> roles, String homePath, boolean systemAdministrator) {
        public CurrentUser { roles = List.copyOf(roles); }
    }

    public record User(long id, long membershipId, String username, String name, Long departmentId,
                       String departmentName, List<Long> roleIds, List<String> roleNames, int status,
                       String identityStatus, long userVersion, long identityVersion,
                       long credentialVersion, String remark, Instant createdAt) {
        public User { roleIds = List.copyOf(roleIds); roleNames = List.copyOf(roleNames); }
    }

    public record Role(long id, String name, List<Long> menuIds, int status, String remark,
                       long rowVersion, boolean systemRole, boolean assignable, Instant createdAt) {
        public Role { menuIds = List.copyOf(menuIds); }
    }

    public record Department(long id, Long parentId, String name, int status, String remark,
                             long rowVersion, boolean systemManaged, Instant createdAt) {
        public Department(long id, Long parentId, String name, int status, String remark,
                          long rowVersion, Instant createdAt) {
            this(id, parentId, name, status, remark, rowVersion, false, createdAt);
        }
    }

    public record Menu(long id, Long parentId, String type, String name, String path, String component,
                       String redirect, String authCode, String metaJson, int status, long rowVersion,
                       boolean systemManaged) {
        public Menu(long id, Long parentId, String type, String name, String path, String component,
                    String redirect, String authCode, String metaJson, int status, long rowVersion) {
            this(id, parentId, type, name, path, component, redirect, authCode, metaJson,
                status, rowVersion, false);
        }
    }

    public record UserCreateCommand(String username, String name, long departmentId, List<Long> roleIds,
                                    int status, String remark) {
        public UserCreateCommand { roleIds = List.copyOf(roleIds); }
    }

    public record MembershipUpdateCommand(String username, String name, long departmentId,
                                          List<Long> roleIds, int status, long userVersion,
                                          Long identityVersion, Long credentialVersion, String remark) {
        public MembershipUpdateCommand { roleIds = List.copyOf(roleIds); }

        public MembershipUpdateCommand(long departmentId, List<Long> roleIds,
                                       int status, long userVersion) {
            this(null, null, departmentId, roleIds, status, userVersion, null, null, null);
        }
    }

    public record RoleCommand(String name, List<Long> menuIds, int status, String remark) {
        public RoleCommand { menuIds = List.copyOf(menuIds); }
    }

    public record DepartmentCommand(Long parentId, String name, int status, String remark) { }

    public record MenuCommand(Long parentId, String type, String name, String path, String component,
                              String redirect, String authCode, String metaJson, int status) { }

    public record UserQuery(String username, String name, Long id, Integer status, Long departmentId,
                            Instant startTime, Instant endTime, int page, int pageSize) { }

    public record RoleQuery(String name, Long id, Integer status, String remark,
                            Instant startTime, Instant endTime, int page, int pageSize) { }
}
