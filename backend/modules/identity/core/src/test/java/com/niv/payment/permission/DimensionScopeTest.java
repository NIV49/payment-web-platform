package com.niv.payment.permission;

import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DimensionScopeTest {
    private static final Map<ScopeDimension, Set<ScopeMode>> ALLOWED_MODES = Map.of(
        ScopeDimension.TENANT, Set.of(ScopeMode.TENANT_ALL),
        ScopeDimension.OWNER, Set.of(ScopeMode.SELF),
        ScopeDimension.DEPARTMENT, Set.of(
            ScopeMode.SELF,
            ScopeMode.DEPARTMENT,
            ScopeMode.DEPARTMENT_AND_CHILDREN,
            ScopeMode.SPECIFIED),
        ScopeDimension.CUSTOMER, Set.of(
            ScopeMode.ASSIGNED,
            ScopeMode.SPECIFIED),
        ScopeDimension.MERCHANT, Set.of(
            ScopeMode.ASSIGNED,
            ScopeMode.SPECIFIED,
            ScopeMode.RELATION_CURRENT,
            ScopeMode.RELATION_AT_EVENT),
        ScopeDimension.MARKET, Set.of(ScopeMode.SPECIFIED),
        ScopeDimension.CHANNEL, Set.of(ScopeMode.SPECIFIED));

    @Test
    void acceptsOnlyTheDocumentedDimensionModeMatrix() {
        for (ScopeDimension dimension : ScopeDimension.values()) {
            for (ScopeMode mode : ScopeMode.values()) {
                String combination = dimension + " + " + mode;
                if (ALLOWED_MODES.get(dimension).contains(mode)) {
                    assertDoesNotThrow(
                        () -> new DimensionScope(dimension, mode, targetsFor(mode)),
                        combination);
                } else {
                    assertThrows(
                        IllegalArgumentException.class,
                        () -> new DimensionScope(dimension, mode, targetsFor(mode)),
                        combination);
                }
            }
        }
    }

    @Test
    void invalidCombinationErrorIdentifiesBothDimensionAndMode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            new DimensionScope(ScopeDimension.TENANT, ScopeMode.DEPARTMENT, Set.of()));

        org.junit.jupiter.api.Assertions.assertAll(
            () -> org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("TENANT")),
            () -> org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("DEPARTMENT")));
    }

    @Test
    void assignedScopeDoesNotRequireOrRetainStaticTargets() {
        assertDoesNotThrow(() ->
            new DimensionScope(ScopeDimension.MERCHANT, ScopeMode.ASSIGNED, Set.of()));

        DimensionScope scope = new DimensionScope(
            ScopeDimension.MERCHANT, ScopeMode.ASSIGNED, Set.of("stale-target"));

        assertEquals(Set.of(), scope.targets());
    }

    private static Set<String> targetsFor(ScopeMode mode) {
        return mode == ScopeMode.SPECIFIED
            ? Set.of("target")
            : Set.of();
    }
}
