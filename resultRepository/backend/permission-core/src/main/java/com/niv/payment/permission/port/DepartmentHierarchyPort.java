package com.niv.payment.permission.port;

@FunctionalInterface
public interface DepartmentHierarchyPort {
    boolean contains(long ancestorDepartmentId, long childDepartmentId);
}
