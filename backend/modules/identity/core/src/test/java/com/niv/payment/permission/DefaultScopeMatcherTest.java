package com.niv.payment.permission;

import com.niv.payment.permission.application.DefaultScopeMatcher;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.DimensionScope;
import com.niv.payment.permission.domain.ResourceContext;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultScopeMatcherTest {

    @Test
    void assignedScopeCannotUseStaticTargetsWhenRelationshipProviderRejects() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        DefaultScopeMatcher matcher = new DefaultScopeMatcher(
            (ancestor, child) -> false,
            (subject, scope, resource) -> {
                providerCalled.set(true);
                return false;
            });
        DimensionScope assignedMerchant = new DimensionScope(
            ScopeDimension.MERCHANT, ScopeMode.ASSIGNED, Set.of("M1"));

        boolean matches = matcher.matches(subject(), assignedMerchant, resource("M1"));

        assertTrue(providerCalled.get(), "ASSIGNED must consult the trusted relationship provider");
        assertFalse(matches, "a stale static target must not survive relationship rejection or unbinding");
    }

    @Test
    void assignedScopeMatchesOnlyWhenRelationshipProviderConfirms() {
        DefaultScopeMatcher matcher = new DefaultScopeMatcher(
            (ancestor, child) -> false,
            (subject, scope, resource) -> {
                assertEquals(Set.of(), scope.targets());
                return "M2".equals(resource.merchantRef());
            });
        DimensionScope staleStaticTarget = new DimensionScope(
            ScopeDimension.MERCHANT, ScopeMode.ASSIGNED, Set.of("M1"));

        assertTrue(matcher.matches(subject(), staleStaticTarget, resource("M2")));
    }

    @Test
    void assignedScopeFailsClosedWhenRelationshipProviderThrows() {
        DefaultScopeMatcher matcher = new DefaultScopeMatcher(
            (ancestor, child) -> false,
            (subject, scope, resource) -> {
                throw new IllegalStateException("relationship provider unavailable");
            });

        assertFalse(matcher.matches(subject(),
            new DimensionScope(ScopeDimension.MERCHANT, ScopeMode.ASSIGNED, Set.of()),
            resource("M1")));
    }

    @Test
    void matcherCannotBeConstructedWithoutRelationshipProvider() {
        assertThrows(NullPointerException.class,
            () -> new DefaultScopeMatcher((ancestor, child) -> false, null));
    }

    @Test
    void specifiedScopeKeepsStaticTargetMatchingWithoutCallingRelationshipProvider() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        DefaultScopeMatcher matcher = new DefaultScopeMatcher(
            (ancestor, child) -> false,
            (subject, scope, resource) -> {
                providerCalled.set(true);
                return false;
            });
        DimensionScope specifiedMerchant = new DimensionScope(
            ScopeDimension.MERCHANT, ScopeMode.SPECIFIED, Set.of("M1"));

        assertTrue(matcher.matches(subject(), specifiedMerchant, resource("M1")));
        assertFalse(providerCalled.get(), "SPECIFIED must remain a static target match");
    }

    private static AuthorizationSubject subject() {
        return new AuthorizationSubject(100L, 200L, 300L, 400L, 7L, 3L, false);
    }

    private static ResourceContext resource(String merchantRef) {
        return new ResourceContext(300L, 999L, null, null, merchantRef, "PK", null);
    }
}
