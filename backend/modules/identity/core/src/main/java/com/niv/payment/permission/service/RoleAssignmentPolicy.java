package com.niv.payment.permission.service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed policy for ordinary membership role replacement.
 *
 * <p>System and non-assignable roles are managed only by a future audited
 * break-glass/provisioning workflow. An ordinary IAM administrator may assign
 * normal roles; a non-system administrator may only delegate roles they
 * already hold.</p>
 */
public final class RoleAssignmentPolicy {

    public void validateReplacement(long operatorMembershipId,
                                    long targetMembershipId,
                                    Set<Long> currentRoleIds,
                                    Set<Long> requestedRoleIds,
                                    Set<Long> operatorRoleIds,
                                    Map<Long, RoleFacts> roleFacts) {
        Objects.requireNonNull(currentRoleIds, "currentRoleIds");
        Objects.requireNonNull(requestedRoleIds, "requestedRoleIds");
        Objects.requireNonNull(operatorRoleIds, "operatorRoleIds");
        Objects.requireNonNull(roleFacts, "roleFacts");

        Set<Long> added = difference(requestedRoleIds, currentRoleIds);
        Set<Long> removed = difference(currentRoleIds, requestedRoleIds);
        boolean operatorIsSystemAdministrator = operatorRoleIds.stream()
            .map(roleFacts::get)
            .filter(Objects::nonNull)
            .anyMatch(role -> role.active() && role.systemRole());

        for (Long roleId : added) {
            RoleFacts role = requireFacts(roleFacts, roleId);
            if (!role.active() || !role.assignable() || role.systemRole()) {
                throw new RoleNotAssignableException();
            }
            if (operatorMembershipId == targetMembershipId) {
                throw new RoleNotAssignableException();
            }
            if (!operatorIsSystemAdministrator
                && (!role.active() || !operatorRoleIds.contains(roleId))) {
                throw new RoleNotAssignableException();
            }
        }

        for (Long roleId : removed) {
            RoleFacts role = requireFacts(roleFacts, roleId);
            if (!role.active() && !operatorIsSystemAdministrator) {
                throw new RoleNotAssignableException();
            }
            if (!role.assignable() || role.systemRole()) {
                throw new RoleNotAssignableException();
            }
            if (!operatorIsSystemAdministrator && !operatorRoleIds.contains(roleId)) {
                throw new RoleNotAssignableException();
            }
        }
    }

    public void validateDeactivation(boolean targetHasSystemRole,
                                     boolean anotherActiveSystemAdministratorExists) {
        if (targetHasSystemRole && !anotherActiveSystemAdministratorExists) {
            throw new LastAdministratorException();
        }
    }

    private static RoleFacts requireFacts(Map<Long, RoleFacts> roleFacts, Long roleId) {
        RoleFacts facts = roleFacts.get(roleId);
        if (facts == null) {
            throw new RoleNotAssignableException();
        }
        return facts;
    }

    private static Set<Long> difference(Set<Long> left, Set<Long> right) {
        java.util.LinkedHashSet<Long> result = new java.util.LinkedHashSet<>(left);
        result.removeAll(right);
        return Set.copyOf(result);
    }

    public record RoleFacts(long id, boolean assignable, boolean systemRole, boolean active) {
        public RoleFacts {
            if (id <= 0) {
                throw new IllegalArgumentException("Role identifier must be positive");
            }
        }
    }

    public static final class RoleNotAssignableException extends RuntimeException {
        public RoleNotAssignableException() {
            super("The requested role change is not assignable through the ordinary workflow");
        }
    }

    public static final class LastAdministratorException extends RuntimeException {
        public LastAdministratorException() {
            super("The last active system administrator is protected");
        }
    }
}
