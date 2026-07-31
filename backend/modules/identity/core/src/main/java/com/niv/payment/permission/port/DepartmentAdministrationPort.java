package com.niv.payment.permission.port;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.service.IdentityModels;

import java.util.List;

public interface DepartmentAdministrationPort {
    List<IdentityModels.Department> findDepartments(long tenantId);
    default List<IdentityModels.Department> findDepartments(long tenantId, boolean selectableOnly) {
        return findDepartments(tenantId).stream()
            .filter(department -> !selectableOnly || department.status() == 1)
            .toList();
    }
    long createDepartment(long tenantId, AdministrationActor actor, IdentityModels.DepartmentCommand command);
    void updateDepartment(long tenantId, AdministrationActor actor, long departmentId,
                          IdentityModels.DepartmentCommand command, long expectedVersion);
    void deleteDepartment(long tenantId, AdministrationActor actor, long departmentId, long expectedVersion);
}
