package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.PlatformUserGovernancePort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.LoginCredentialPolicy;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

/** PostgreSQL adapter for the PLATFORM identity-management control plane. */
public class JooqPlatformUserGovernanceRepository implements PlatformUserGovernancePort {
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String TERMINATED = "TERMINATED";

    private final DSLContext dsl;
    private final JooqAdministrationSupport source;
    private final JooqPlatformSystemAdministratorGuard systemAdministrator;
    private final Supplier<String> initialPasswordHashSupplier;
    private final BooleanSupplier localPasswordResetEnabled;
    private final Function<String, String> passwordHasher;
    private final Supplier<String> traceIdSupplier;

    public JooqPlatformUserGovernanceRepository(
        DSLContext dsl, Supplier<String> traceIdSupplier,
        Supplier<String> initialPasswordHashSupplier) {
        this(dsl, traceIdSupplier, initialPasswordHashSupplier,
            () -> false, ignored -> null);
    }

    public JooqPlatformUserGovernanceRepository(
        DSLContext dsl, Supplier<String> traceIdSupplier,
        Supplier<String> initialPasswordHashSupplier,
        BooleanSupplier localPasswordResetEnabled,
        Function<String, String> passwordHasher) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
        this.source = new JooqAdministrationSupport(dsl, AccountDomain.PLATFORM, traceIdSupplier);
        this.systemAdministrator = new JooqPlatformSystemAdministratorGuard(dsl);
        this.initialPasswordHashSupplier = Objects.requireNonNull(
            initialPasswordHashSupplier, "initialPasswordHashSupplier");
        this.localPasswordResetEnabled = Objects.requireNonNull(
            localPasswordResetEnabled, "localPasswordResetEnabled");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
    }

    @Override
    public IdentityModels.Page<DirectoryUser> findUsers(
        long sourceTenantId, AdministrationActor actor, DirectoryQuery query) {
        source.validateActor(sourceTenantId, actor);
        requirePlatformSystemAdministrator(sourceTenantId, actor.membershipId());

        Condition condition = IAM_TENANT.ACCOUNT_DOMAIN.eq(query.accountDomain().name())
            .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED));
        if (query.departmentId() != null && query.accountDomain() != AccountDomain.PLATFORM) {
            throw new SecurityException("Department filtering is limited to PLATFORM accounts");
        }
        if (query.accountDomain() == AccountDomain.PLATFORM) {
            if (query.tenantId() != null && query.tenantId() != sourceTenantId) {
                throw new SecurityException("PLATFORM directory is limited to the current tenant");
            }
            condition = condition.and(IAM_TENANT.ID.eq(sourceTenantId));
            if (query.departmentId() != null) {
                condition = condition.and(IAM_MEMBERSHIP.DEPARTMENT_ID.eq(query.departmentId()));
            }
        } else if (query.tenantId() != null) {
            condition = condition.and(IAM_TENANT.ID.eq(query.tenantId()));
        }
        if (query.email() != null) {
            condition = condition.and(IAM_AUTHENTICATION_CREDENTIAL.USERNAME
                .containsIgnoreCase(query.email()));
        }
        if (query.name() != null) {
            condition = condition.and(IAM_USER.DISPLAY_NAME.containsIgnoreCase(query.name()));
        }
        if (query.status() != null) {
            condition = condition.and(IAM_MEMBERSHIP.STATUS.eq(status(query.status())));
        }

        var rows = dsl.select(
                IAM_USER.ID, IAM_MEMBERSHIP.ID, IAM_TENANT.ID, IAM_TENANT.TENANT_NAME,
                IAM_TENANT.ACCOUNT_DOMAIN, IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_USER.DISPLAY_NAME, IAM_USER.REMARK, IAM_MEMBERSHIP.DEPARTMENT_ID,
                IAM_DEPARTMENT.DEPARTMENT_NAME, IAM_MEMBERSHIP.STATUS, IAM_USER.STATUS,
                IAM_MEMBERSHIP.ROW_VERSION, IAM_USER.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION, IAM_MEMBERSHIP.CREATED_AT)
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID))
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID))
            .leftJoin(IAM_DEPARTMENT).on(IAM_DEPARTMENT.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(IAM_DEPARTMENT.ID.eq(IAM_MEMBERSHIP.DEPARTMENT_ID))
                .and(IAM_DEPARTMENT.DELETED_AT.isNull()))
            .where(condition)
            .orderBy(IAM_MEMBERSHIP.CREATED_AT.desc(), IAM_MEMBERSHIP.ID.desc())
            .limit(query.pageSize())
            .offset((query.page() - 1) * query.pageSize())
            .fetch();

        List<Long> membershipIds = rows.getValues(IAM_MEMBERSHIP.ID);
        Map<Long, RoleSummary> roles = roleSummaries(membershipIds);
        List<DirectoryUser> users = rows.stream().map(row -> {
            RoleSummary role = roles.getOrDefault(row.get(IAM_MEMBERSHIP.ID), RoleSummary.EMPTY);
            return new DirectoryUser(
                row.get(IAM_USER.ID), row.get(IAM_MEMBERSHIP.ID),
                AccountDomain.valueOf(row.get(IAM_TENANT.ACCOUNT_DOMAIN)), row.get(IAM_TENANT.ID),
                row.get(IAM_TENANT.TENANT_NAME), row.get(IAM_AUTHENTICATION_CREDENTIAL.USERNAME),
                row.get(IAM_USER.DISPLAY_NAME), row.get(IAM_USER.REMARK),
                row.get(IAM_MEMBERSHIP.DEPARTMENT_ID),
                row.get(IAM_DEPARTMENT.DEPARTMENT_NAME), role.ids(), role.names(),
                apiStatus(row.get(IAM_MEMBERSHIP.STATUS)),
                row.get(IAM_USER.STATUS), role.systemAdministrator(), row.get(IAM_MEMBERSHIP.ROW_VERSION),
                row.get(IAM_USER.ROW_VERSION), row.get(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION),
                row.get(IAM_MEMBERSHIP.CREATED_AT).atZoneSameInstant(ZoneOffset.UTC).toInstant());
        }).toList();
        long total = dsl.selectCount()
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID))
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID))
            .where(condition)
            .fetchOne(0, long.class);
        return new IdentityModels.Page<>(users, total);
    }

    @Override
    public List<TenantOption> findTargetTenants(
        long sourceTenantId, AdministrationActor actor, AccountDomain accountDomain) {
        source.validateActor(sourceTenantId, actor);
        requirePlatformSystemAdministrator(sourceTenantId, actor.membershipId());
        return dsl.select(IAM_TENANT.ID, IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME)
            .from(IAM_TENANT)
            .where(IAM_TENANT.ACCOUNT_DOMAIN.eq(accountDomain.name())
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .orderBy(IAM_TENANT.TENANT_NAME, IAM_TENANT.ID)
            .fetch(row -> new TenantOption(row.value1(), accountDomain, row.value2(), row.value3()));
    }

    @Override
    @Transactional
    public long createTenantAdministrator(
        long sourceTenantId, AdministrationActor actor, CreateAdministratorCommand command) {
        source.lockTenant(sourceTenantId, actor);
        requirePlatformSystemAdministrator(sourceTenantId, actor.membershipId());
        lockTargetTenant(command.tenantId(), command.accountDomain());
        long departmentId = rootDepartment(command.tenantId());
        long roleId = systemRole(command.tenantId());
        String passwordHash = requiredInitialPasswordHash();
        long userId = source.nextId();
        long membershipId = source.nextId();
        try {
            dsl.insertInto(IAM_USER)
                .set(IAM_USER.ID, userId)
                .set(IAM_USER.ACCOUNT_DOMAIN, command.accountDomain().name())
                .set(IAM_USER.IDP_ISSUER, localIssuer(command.accountDomain()))
                .set(IAM_USER.IDP_SUBJECT, command.email())
                .set(IAM_USER.DISPLAY_NAME, command.name())
                .set(IAM_USER.STATUS, ACTIVE)
                .set(IAM_USER.REMARK, "Created by the platform identity management plane")
                .execute();
            dsl.insertInto(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.ID, membershipId)
                .set(IAM_MEMBERSHIP.TENANT_ID, command.tenantId())
                .set(IAM_MEMBERSHIP.USER_ID, userId)
                .set(IAM_MEMBERSHIP.DEPARTMENT_ID, departmentId)
                .set(IAM_MEMBERSHIP.STATUS, status(command.status()))
                .set(IAM_MEMBERSHIP.ACCOUNT_DOMAIN, command.accountDomain().name())
                .execute();
            dsl.insertInto(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.USER_ID, userId)
                .set(IAM_AUTHENTICATION_CREDENTIAL.USERNAME, command.email())
                .set(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, passwordHash)
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, ACTIVE)
                .set(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN, command.accountDomain().name())
                .execute();
            dsl.insertInto(IAM_MEMBERSHIP_ROLE)
                .set(IAM_MEMBERSHIP_ROLE.TENANT_ID, command.tenantId())
                .set(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID, membershipId)
                .set(IAM_MEMBERSHIP_ROLE.ROLE_ID, roleId)
                .setNull(IAM_MEMBERSHIP_ROLE.ASSIGNED_BY)
                .execute();
        } catch (IntegrityConstraintViolationException conflict) {
            throw new IdentityAdministrationService.DataConflictException(
                "The target administrator already exists or violates the account domain boundary");
        }
        audit(command.tenantId(), actor, userId, membershipId,
            command.accountDomain(), "CREATE_TENANT_SYSTEM_ADMINISTRATOR");
        return userId;
    }

    @Override
    @Transactional
    public void updateTenantAdministrator(
        long sourceTenantId, AdministrationActor actor, long userId,
        UpdateAdministratorCommand command) {
        source.lockTenant(sourceTenantId, actor);
        requirePlatformSystemAdministrator(sourceTenantId, actor.membershipId());
        lockTargetTenant(command.tenantId(), command.accountDomain());
        long protectedRoleId = systemRole(command.tenantId());
        var target = dsl.select(
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.STATUS, IAM_MEMBERSHIP.ROW_VERSION,
                IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT, IAM_USER.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.USERNAME,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID))
            .join(IAM_MEMBERSHIP_ROLE)
                .on(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID)))
            .join(IAM_ROLE)
                .on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                    .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID))
                    .and(IAM_ROLE.ID.eq(protectedRoleId))
                    .and(IAM_ROLE.STATUS.eq(ACTIVE))
                    .and(IAM_ROLE.DELETED_AT.isNull()))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(command.tenantId())
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(command.accountDomain().name()))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .forUpdate()
            .of(IAM_MEMBERSHIP, IAM_USER, IAM_AUTHENTICATION_CREDENTIAL)
            .fetchOne();
        if (target == null) throw JooqAdministrationSupport.notFound("Tenant administrator");
        if (target.get(IAM_MEMBERSHIP.ROW_VERSION) != command.membershipVersion()
            || target.get(IAM_USER.ROW_VERSION) != command.identityVersion()
            || target.get(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION) != command.credentialVersion()) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        boolean emailChanged = !command.email().equals(target.get(IAM_AUTHENTICATION_CREDENTIAL.USERNAME));
        if (emailChanged && !localIssuer(command.accountDomain()).equals(target.get(IAM_USER.IDP_ISSUER))) {
            throw new IdentityAdministrationService.DataConflictException(
                "External identity email must be changed in the identity provider");
        }
        if (command.status() == 0 && ACTIVE.equals(target.get(IAM_MEMBERSHIP.STATUS))) {
            protectLastAdministrator(command.tenantId(), target.get(IAM_MEMBERSHIP.ID));
        }
        int membershipUpdated = dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.STATUS, status(command.status()))
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.ID.eq(target.get(IAM_MEMBERSHIP.ID))
                .and(IAM_MEMBERSHIP.ROW_VERSION.eq(command.membershipVersion())))
            .execute();
        var userUpdate = dsl.update(IAM_USER)
            .set(IAM_USER.DISPLAY_NAME, command.name())
            .set(IAM_USER.ROW_VERSION, IAM_USER.ROW_VERSION.plus(1L))
            .set(IAM_USER.UPDATED_AT, DSL.currentOffsetDateTime());
        if (emailChanged) userUpdate.set(IAM_USER.IDP_SUBJECT, command.email());
        int userUpdated = userUpdate.where(IAM_USER.ID.eq(userId)
            .and(IAM_USER.ROW_VERSION.eq(command.identityVersion()))).execute();
        int credentialUpdated = emailChanged
            ? dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.USERNAME, command.email())
                .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION,
                    IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.plus(1L))
                .set(IAM_AUTHENTICATION_CREDENTIAL.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(userId)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.eq(command.credentialVersion())))
                .execute()
            : 1;
        if (membershipUpdated != 1 || userUpdated != 1 || credentialUpdated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        if (emailChanged) {
            dsl.update(IAM_MEMBERSHIP)
                .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
                .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(IAM_MEMBERSHIP.USER_ID.eq(userId)
                    .and(IAM_MEMBERSHIP.ID.ne(target.get(IAM_MEMBERSHIP.ID)))
                    .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
                .execute();
        }
        audit(command.tenantId(), actor, userId, target.get(IAM_MEMBERSHIP.ID),
            command.accountDomain(), "UPDATE_TENANT_SYSTEM_ADMINISTRATOR");
    }

    @Override
    @Transactional
    public IdentityModels.PasswordResetResult resetUserPassword(
        long sourceTenantId, AdministrationActor actor, long userId,
        PasswordResetCommand command) {
        requireLocalPasswordResetEnabled();
        source.lockTenant(sourceTenantId, actor);
        requirePlatformSystemAdministrator(sourceTenantId, actor.membershipId());
        lockTargetTenant(command.tenantId(), command.accountDomain());
        String domain = command.accountDomain().name();

        var target = dsl.select(
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.ROW_VERSION,
                IAM_USER.IDP_ISSUER, IAM_USER.STATUS, IAM_USER.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.STATUS,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                .and(IAM_USER.ACCOUNT_DOMAIN.eq(domain)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain)))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(command.tenantId())
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .forUpdate()
            .of(IAM_MEMBERSHIP, IAM_USER, IAM_AUTHENTICATION_CREDENTIAL)
            .fetchOne();
        if (target == null) throw JooqAdministrationSupport.notFound("User");
        if (!localIssuer(command.accountDomain()).equals(target.get(IAM_USER.IDP_ISSUER))) {
            throw new IdentityAdministrationService.DataConflictException(
                "External identity password cannot be reset");
        }
        if (!ACTIVE.equals(target.get(IAM_USER.STATUS))
            || !ACTIVE.equals(target.get(IAM_AUTHENTICATION_CREDENTIAL.STATUS))) {
            throw new IdentityAdministrationService.DataConflictException(
                "Inactive local identity password cannot be reset");
        }
        if (!Objects.equals(target.get(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION),
            command.credentialVersion())) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }

        String passwordHash = hashPassword(command.password());
        int credentialUpdated = dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH, passwordHash)
            .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.plus(1L))
            .set(IAM_AUTHENTICATION_CREDENTIAL.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(userId)
                .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.eq(command.credentialVersion())))
            .execute();
        if (credentialUpdated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        int identityUpdated = dsl.update(IAM_USER)
            .set(IAM_USER.ROW_VERSION, IAM_USER.ROW_VERSION.plus(1L))
            .set(IAM_USER.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_USER.ID.eq(userId).and(IAM_USER.ACCOUNT_DOMAIN.eq(domain)))
            .execute();
        if (identityUpdated != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        int affectedMemberships = dsl.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IAM_MEMBERSHIP.USER_ID.eq(userId)
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.STATUS.ne(TERMINATED)))
            .execute();
        if (affectedMemberships < 1) throw JooqAdministrationSupport.notFound("User");

        audit(command.tenantId(), actor, userId, target.get(IAM_MEMBERSHIP.ID),
            command.accountDomain(), "USER", "RESET_LOCAL_PASSWORD");
        return new IdentityModels.PasswordResetResult(
            command.credentialVersion() + 1,
            target.get(IAM_USER.ROW_VERSION) + 1,
            target.get(IAM_MEMBERSHIP.ROW_VERSION) + 1);
    }

    private Map<Long, RoleSummary> roleSummaries(List<Long> membershipIds) {
        if (membershipIds.isEmpty()) return Map.of();
        Map<Long, RoleAccumulator> values = new LinkedHashMap<>();
        dsl.select(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID, IAM_ROLE.ID,
                IAM_ROLE.ROLE_NAME, IAM_ROLE.SYSTEM_ROLE, IAM_ROLE.ASSIGNABLE)
            .from(IAM_MEMBERSHIP_ROLE)
            .join(IAM_ROLE).on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID))
                .and(IAM_ROLE.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .where(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.in(membershipIds))
            .orderBy(IAM_ROLE.ROLE_NAME)
            .forEach(row -> values.computeIfAbsent(row.value1(), ignored -> new RoleAccumulator())
                .add(row.value2(), row.value3(), Boolean.TRUE.equals(row.value4()),
                    Boolean.TRUE.equals(row.value5())));
        Map<Long, RoleSummary> result = new LinkedHashMap<>();
        values.forEach((id, value) -> result.put(id, value.summary()));
        return result;
    }

    private void requirePlatformSystemAdministrator(long tenantId, long membershipId) {
        systemAdministrator.require(tenantId, membershipId);
    }

    private void lockTargetTenant(long tenantId, AccountDomain accountDomain) {
        Long locked = dsl.select(IAM_TENANT.ID).from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(tenantId)
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(accountDomain.name()))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .forUpdate().fetchOne(IAM_TENANT.ID);
        if (locked == null) throw JooqAdministrationSupport.notFound("Target tenant");
    }

    private long rootDepartment(long tenantId) {
        List<Long> ids = dsl.select(IAM_DEPARTMENT.ID).from(IAM_DEPARTMENT)
            .where(IAM_DEPARTMENT.TENANT_ID.eq(tenantId)
                .and(IAM_DEPARTMENT.PARENT_ID.isNull())
                .and(IAM_DEPARTMENT.SYSTEM_MANAGED.isTrue())
                .and(IAM_DEPARTMENT.STATUS.eq(ACTIVE))
                .and(IAM_DEPARTMENT.DELETED_AT.isNull()))
            .orderBy(IAM_DEPARTMENT.ID).limit(2).fetch(IAM_DEPARTMENT.ID);
        if (ids.size() != 1) throw new IdentityAdministrationService.DataConflictException(
            "Target tenant must have exactly one active system root department");
        return ids.getFirst();
    }

    private long systemRole(long tenantId) {
        return systemAdministrator.protectedSystemRole(tenantId);
    }

    private void protectLastAdministrator(long tenantId, long membershipId) {
        long protectedRoleId = systemRole(tenantId);
        boolean replacementExists = dsl.selectDistinct(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE)))
            .join(IAM_MEMBERSHIP_ROLE)
                .on(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID)))
            .join(IAM_ROLE).on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.ne(membershipId))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.ID.eq(protectedRoleId))
                .and(IAM_ROLE.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.DELETED_AT.isNull()))
            .fetch(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .stream()
            .anyMatch(LoginCredentialPolicy::isLoginCapableHash);
        if (!replacementExists) throw new com.niv.payment.permission.service.RoleAssignmentPolicy
            .LastAdministratorException();
    }

    private String requiredInitialPasswordHash() {
        String hash = initialPasswordHashSupplier.get();
        if (hash == null || !LoginCredentialPolicy.isLoginCapableHash(hash)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Local tenant administrator creation requires a configured bootstrap password");
        }
        return hash;
    }

    private void requireLocalPasswordResetEnabled() {
        if (!localPasswordResetEnabled.getAsBoolean()) {
            throw new IdentityAdministrationService.DataConflictException(
                "Local password reset is unavailable");
        }
    }

    private String hashPassword(String password) {
        String hash = passwordHasher.apply(password);
        if (!LoginCredentialPolicy.isLoginCapableHash(hash)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Local password reset is unavailable");
        }
        return hash;
    }

    private void audit(long targetTenantId, AdministrationActor actor, long userId,
                       long membershipId, AccountDomain accountDomain, String action) {
        audit(targetTenantId, actor, userId, membershipId, accountDomain,
            "TENANT_SYSTEM_ADMINISTRATOR", action);
    }

    private void audit(long targetTenantId, AdministrationActor actor, long userId,
                       long membershipId, AccountDomain accountDomain,
                       String targetType, String action) {
        String evidence = "{\"sourceTenantId\":" + sourceTenantId(actor)
            + ",\"sourceMembershipId\":" + actor.membershipId()
            + ",\"sourceUserId\":" + actor.expectedUserId()
            + ",\"targetMembershipId\":" + membershipId
            + ",\"targetAccountDomain\":\"" + accountDomain.name() + "\"}";
        dsl.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, source.nextId())
            .set(IAM_AUDIT_EVENT.TENANT_ID, targetTenantId)
            .setNull(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID)
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, targetType)
            .set(IAM_AUDIT_EVENT.TARGET_REF, Long.toString(userId))
            .set(IAM_AUDIT_EVENT.ACTION_CODE, action)
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, "PLATFORM_CONTROL_PLANE")
            .setNull(IAM_AUDIT_EVENT.PERMISSION_CODE)
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, JSONB.valueOf(evidence))
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceIdSupplier.get())
            .execute();
    }

    private long sourceTenantId(AdministrationActor actor) {
        Long tenantId = dsl.select(IAM_MEMBERSHIP.TENANT_ID).from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.ID.eq(actor.membershipId())
                .and(IAM_MEMBERSHIP.USER_ID.eq(actor.expectedUserId())))
            .fetchOne(IAM_MEMBERSHIP.TENANT_ID);
        if (tenantId == null) throw new SecurityException("Platform actor is unavailable");
        return tenantId;
    }

    private static String localIssuer(AccountDomain domain) {
        return "local:" + domain.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String status(int value) {
        return value == 1 ? ACTIVE : DISABLED;
    }

    private static int apiStatus(String value) {
        return ACTIVE.equals(value) ? 1 : 0;
    }

    private record RoleSummary(List<Long> ids, List<String> names, boolean systemAdministrator) {
        private static final RoleSummary EMPTY = new RoleSummary(List.of(), List.of(), false);
    }

    private static final class RoleAccumulator {
        private final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        private final java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        private boolean systemAdministrator;

        void add(Long id, String name, boolean systemRole, boolean assignable) {
            ids.add(id);
            names.add(name);
            systemAdministrator |= systemRole && !assignable;
        }

        RoleSummary summary() {
            return new RoleSummary(List.copyOf(ids), List.copyOf(names), systemAdministrator);
        }
    }
}
