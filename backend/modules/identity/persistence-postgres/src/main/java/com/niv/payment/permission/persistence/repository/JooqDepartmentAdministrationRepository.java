package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.ACTIVE;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.DISABLED;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.notFound;
import static com.niv.payment.permission.persistence.repository.JooqAdministrationSupport.status;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_DEPTH;
import static com.niv.payment.permission.service.IdentityAdministrationService.MAX_TREE_NODES;

/** Department-tree adapter. Graph validation runs under the tenant write lock. */
public class JooqDepartmentAdministrationRepository implements DepartmentAdministrationPort {
    private final DSLContext dsl;
    private final JooqIdentityQueryRepository queries;
    private final JooqAdministrationSupport support;

    public JooqDepartmentAdministrationRepository(DSLContext dsl,
                                                  JooqIdentityQueryRepository queries,
                                                  Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.support = new JooqAdministrationSupport(dsl, traceIdSupplier);
    }

    @Override
    public List<IdentityModels.Department> findDepartments(long tenantId) {
        return queries.findDepartments(tenantId);
    }

    @Override
    @Transactional
    public long createDepartment(long tenantId, AdministrationActor actor,
                                 IdentityModels.DepartmentCommand command) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        Map<Long, TreeNode> nodes = nodes(tenantId);
        if (nodes.size() >= MAX_TREE_NODES) {
            throw new IdentityAdministrationService.TreeLimitExceededException("Tree node limit exceeded");
        }
        long departmentId = support.nextId();
        String state = status(command.status());
        if (!parentAllowed(nodes, departmentId, command.parentId(), state)) {
            throw new IllegalArgumentException("Invalid or inactive department parent");
        }
        dsl.insertInto(IAM_DEPARTMENT)
            .set(IAM_DEPARTMENT.ID, departmentId)
            .set(IAM_DEPARTMENT.TENANT_ID, tenantId)
            .set(IAM_DEPARTMENT.PARENT_ID, command.parentId())
            .set(IAM_DEPARTMENT.DEPARTMENT_CODE, "dept-" + departmentId)
            .set(IAM_DEPARTMENT.DEPARTMENT_NAME, command.name().trim())
            .set(IAM_DEPARTMENT.STATUS, state)
            .set(IAM_DEPARTMENT.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .execute();
        support.audit(tenantId, actor.membershipId(), "DEPARTMENT", departmentId,
            "CREATE", "department:create");
        return departmentId;
    }

    @Override
    @Transactional
    public void updateDepartment(long tenantId, AdministrationActor actor, long departmentId,
                                 IdentityModels.DepartmentCommand command, long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        Map<Long, TreeNode> nodes = nodes(tenantId);
        if (!nodes.containsKey(departmentId)) {
            throw notFound("Department");
        }
        String state = status(command.status());
        if (!parentAllowed(nodes, departmentId, command.parentId(), state)) {
            throw new IllegalArgumentException("Invalid or inactive department parent");
        }
        if (command.status() == 0 && hasDependents(tenantId, departmentId)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Department has active dependents");
        }
        int updated = dsl.update(IAM_DEPARTMENT)
            .set(IAM_DEPARTMENT.PARENT_ID, command.parentId())
            .set(IAM_DEPARTMENT.DEPARTMENT_NAME, command.name().trim())
            .set(IAM_DEPARTMENT.STATUS, state)
            .set(IAM_DEPARTMENT.REMARK, JooqAdministrationSupport.blankToNull(command.remark()))
            .set(IAM_DEPARTMENT.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_DEPARTMENT.ROW_VERSION, IAM_DEPARTMENT.ROW_VERSION.plus(1L))
            .where(IAM_DEPARTMENT.TENANT_ID.eq(tenantId)
                .and(IAM_DEPARTMENT.ID.eq(departmentId))
                .and(IAM_DEPARTMENT.ROW_VERSION.eq(expectedVersion)))
            .execute();
        requireSuccessfulMutation(updated, tenantId, departmentId);
        support.audit(tenantId, actor.membershipId(), "DEPARTMENT", departmentId,
            "UPDATE", "department:update");
    }

    @Override
    @Transactional
    public void deleteDepartment(long tenantId, AdministrationActor actor, long departmentId,
                                 long expectedVersion) {
        support.requirePlatformTenant(tenantId);
        support.lockTenant(tenantId, actor);
        if (hasDependents(tenantId, departmentId)) {
            throw new IdentityAdministrationService.DataConflictException(
                "Department has active dependents");
        }
        int updated = dsl.update(IAM_DEPARTMENT)
            .set(IAM_DEPARTMENT.STATUS, DISABLED)
            .set(IAM_DEPARTMENT.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_DEPARTMENT.ROW_VERSION, IAM_DEPARTMENT.ROW_VERSION.plus(1L))
            .where(IAM_DEPARTMENT.TENANT_ID.eq(tenantId)
                .and(IAM_DEPARTMENT.ID.eq(departmentId))
                .and(IAM_DEPARTMENT.ROW_VERSION.eq(expectedVersion)))
            .execute();
        requireSuccessfulMutation(updated, tenantId, departmentId);
        support.audit(tenantId, actor.membershipId(), "DEPARTMENT", departmentId,
            "DELETE", "department:delete");
    }

    private boolean parentAllowed(Map<Long, TreeNode> nodes, long departmentId, Long parentId, String state) {
        TreeNode parent = parentId == null ? null : nodes.get(parentId);
        if (parentId != null && (parent == null || (ACTIVE.equals(state) && !ACTIVE.equals(parent.status())))) {
            return false;
        }
        Map<Long, TreeNode> candidate = new LinkedHashMap<>(nodes);
        candidate.put(departmentId, new TreeNode(parentId, state));
        return isValidTree(candidate);
    }

    private void requireSuccessfulMutation(int updated, long tenantId, long departmentId) {
        if (updated == 1) {
            return;
        }
        Long currentVersion = dsl.select(IAM_DEPARTMENT.ROW_VERSION)
            .from(IAM_DEPARTMENT)
            .where(IAM_DEPARTMENT.TENANT_ID.eq(tenantId)
                .and(IAM_DEPARTMENT.ID.eq(departmentId)))
            .fetchOne(IAM_DEPARTMENT.ROW_VERSION);
        if (currentVersion == null) {
            throw notFound("Department");
        }
        throw new IdentityAdministrationService.OptimisticLockException();
    }

    private boolean hasDependents(long tenantId, long departmentId) {
        Map<Long, TreeNode> nodes = nodes(tenantId);
        Set<Long> subtree = subtree(nodes, departmentId);
        boolean hasActiveDescendant = subtree.stream()
            .filter(id -> id != departmentId)
            .map(nodes::get)
            .filter(Objects::nonNull)
            .anyMatch(node -> ACTIVE.equals(node.status()));
        if (hasActiveDescendant) {
            return true;
        }
        return !subtree.isEmpty() && dsl.fetchExists(dsl.selectOne()
            .from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.DEPARTMENT_ID.in(subtree))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE))));
    }

    private Map<Long, TreeNode> nodes(long tenantId) {
        Map<Long, TreeNode> nodes = new LinkedHashMap<>();
        var rows = dsl.select(IAM_DEPARTMENT.ID, IAM_DEPARTMENT.PARENT_ID, IAM_DEPARTMENT.STATUS)
            .from(IAM_DEPARTMENT)
            .where(IAM_DEPARTMENT.TENANT_ID.eq(tenantId))
            .limit(MAX_TREE_NODES + 1)
            .fetch();
        if (rows.size() > MAX_TREE_NODES) {
            throw new IdentityAdministrationService.TreeLimitExceededException("Tree node limit exceeded");
        }
        rows.forEach(row -> nodes.put(row.get(IAM_DEPARTMENT.ID),
                new TreeNode(row.get(IAM_DEPARTMENT.PARENT_ID), row.get(IAM_DEPARTMENT.STATUS))));
        return nodes;
    }

    private static boolean isValidTree(Map<Long, TreeNode> nodes) {
        for (Long startId : nodes.keySet()) {
            Set<Long> visited = new HashSet<>();
            Long current = startId;
            int depth = 0;
            while (current != null) {
                TreeNode node = nodes.get(current);
                if (node == null || !visited.add(current) || ++depth > MAX_TREE_DEPTH) {
                    return false;
                }
                current = node.parentId();
            }
        }
        return true;
    }

    private static Set<Long> subtree(Map<Long, TreeNode> nodes, long rootId) {
        if (!nodes.containsKey(rootId)) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        result.add(rootId);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Long, TreeNode> entry : nodes.entrySet()) {
                if (entry.getValue().parentId() != null
                    && result.contains(entry.getValue().parentId())
                    && result.add(entry.getKey())) {
                    changed = true;
                }
            }
        } while (changed);
        return Set.copyOf(result);
    }

    private record TreeNode(Long parentId, String status) {
    }
}
