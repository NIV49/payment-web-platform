package com.niv.payment.agentadminapi;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCompositionIsolationTest {
    @Test
    void agentRootPackagesMigrationButNoMerchantHttpOrRuntimeConfiguration() {
        assertThat(AgentAdminApiApplication.class.getAnnotation(Import.class).value())
            .noneMatch(type -> type.getName().startsWith("com.niv.payment.merchant"));
        assertThat(ClassUtils.isPresent(
            "com.niv.payment.merchant.web.MerchantSelfHttpConfiguration",
            AgentAdminApiApplication.class.getClassLoader())).isFalse();
        assertThat(ClassUtils.isPresent(
            "com.niv.payment.merchant.persistence.MerchantPersistenceConfiguration",
            AgentAdminApiApplication.class.getClassLoader())).isTrue();
    }
}
