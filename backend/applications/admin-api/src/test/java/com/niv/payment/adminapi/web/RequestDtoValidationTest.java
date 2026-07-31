package com.niv.payment.adminapi.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestDtoValidationTest {
    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void loginFieldsHaveExplicitUpperBounds() {
        assertThat(validator.validate(new AuthUserMenuController.LoginRequest(
            "u".repeat(101), "valid", null))).isNotEmpty();
        assertThat(validator.validate(new AuthUserMenuController.LoginRequest(
            "valid", "p".repeat(257), null))).isNotEmpty();
        assertThat(validator.validate(new AuthUserMenuController.LoginRequest(
            "valid", "valid", "1".repeat(20)))).isNotEmpty();
    }

    @Test
    void everyBatchIdentifierHasANumericLengthBound() {
        String oversizedId = "1".repeat(20);
        var user = new SystemAdministrationController.UserCreateRequest(
            "user", "User", "1", List.of(oversizedId), 1, null);
        var membership = new SystemAdministrationController.MembershipUpdateRequest(
            "1", List.of(oversizedId), 1, 0L);
        var role = new SystemAdministrationController.RoleRequest(
            "Role", List.of(oversizedId), 1, null);

        assertThat(validator.validate(user)).isNotEmpty();
        assertThat(validator.validate(membership)).isNotEmpty();
        assertThat(validator.validate(role)).isNotEmpty();
    }

    @Test
    void menuMetadataHasARootItemLimitBeforeContractValidation() {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 33; index++) meta.put("key" + index, index);
        var menu = new SystemAdministrationController.MenuRequest(
            "0", "menu", "BoundedMeta", "/bounded", "/system/user/list",
            null, null, meta, 1);

        assertThat(validator.validate(menu)).isNotEmpty();
        assertThat(validator.validate(new SystemAdministrationController.MenuRequest(
            "0", "menu", "ValidMeta", "/valid", "/system/user/list",
            null, null, Map.of("title", "system.user.title"), 1))).isEmpty();
    }

    @Test
    void administrationUpdatesRequireANonNegativeExpectedVersion() {
        var missingRoleVersion = new SystemAdministrationController.RoleUpdateRequest(
            "Role", List.of(), 1, null, null);
        var negativeRoleVersion = new SystemAdministrationController.RoleUpdateRequest(
            "Role", List.of(), 1, null, -1L);
        var missingStatusVersion = new SystemAdministrationController.RoleStatusRequest(1, null);
        var missingDepartmentVersion = new SystemAdministrationController.DepartmentUpdateRequest(
            "0", "Department", 1, null, null);
        var missingMenuVersion = new SystemAdministrationController.MenuUpdateRequest(
            "0", "menu", "Menu", "/menu", "/system/user/list",
            null, null, Map.of("title", "system.user.title"), 1, null);

        assertThat(validator.validate(missingRoleVersion)).isNotEmpty();
        assertThat(validator.validate(negativeRoleVersion)).isNotEmpty();
        assertThat(validator.validate(missingStatusVersion)).isNotEmpty();
        assertThat(validator.validate(missingDepartmentVersion)).isNotEmpty();
        assertThat(validator.validate(missingMenuVersion)).isNotEmpty();
    }

    @Test
    void roleGrantListsRejectNullElementsBeforeControllerMapping() {
        var nullGrant = new RoleGrantAdministrationController.ReplaceRoleGrantsRequest(
            0L, "reason", Collections.singletonList(null));
        var nullDimension = new RoleGrantAdministrationController.ReplaceRoleGrantsRequest(
            0L, "reason", List.of(new RoleGrantAdministrationController.GrantRequest(
                "user-view", "user:view",
                Collections.singletonList(null))));

        assertThat(validator.validate(nullGrant)).isNotEmpty();
        assertThat(validator.validate(nullDimension)).isNotEmpty();
    }
}
