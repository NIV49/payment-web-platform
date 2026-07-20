package com.niv.payment.permission.port;

import com.niv.payment.permission.service.IdentityModels;

import java.util.List;

public interface DepartmentAdministrationPort {
    List<IdentityModels.Department> findDepartments(long tenantId);
    long createDepartment(long tenantId, long operatorMembershipId, IdentityModels.DepartmentCommand command);
    void updateDepartment(long tenantId, long operatorMembershipId, long departmentId, IdentityModels.DepartmentCommand command);
    void deleteDepartment(long tenantId, long operatorMembershipId, long departmentId);
}
