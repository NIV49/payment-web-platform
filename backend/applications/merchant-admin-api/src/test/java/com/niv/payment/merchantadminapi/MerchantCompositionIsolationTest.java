package com.niv.payment.merchantadminapi;

import com.niv.payment.merchant.persistence.MerchantPersistenceConfiguration;
import com.niv.payment.merchant.web.MerchantPlatformHttpConfiguration;
import com.niv.payment.merchant.web.MerchantSelfHttpConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantCompositionIsolationTest {
    @Test
    void merchantRootImportsOnlyItsSelfServiceMerchantSurface() {
        assertThat(MerchantAdminApiApplication.class.getAnnotation(Import.class).value())
            .contains(MerchantPersistenceConfiguration.class, MerchantSelfHttpConfiguration.class)
            .doesNotContain(MerchantPlatformHttpConfiguration.class);
    }
}
