package com.niv.payment.adminapi.web;

import com.niv.payment.permission.service.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LocalAuthControllerRegistrationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(AuthenticationService.class, () -> mock(AuthenticationService.class))
        .withUserConfiguration(LocalAuthController.class);

    @Test
    void productionDefaultDoesNotRegisterPasswordLoginController() {
        context.run(result -> assertThat(result).doesNotHaveBean(LocalAuthController.class));
    }

    @Test
    void explicitLocalModeRegistersPasswordLoginController() {
        context.withPropertyValues("payment.identity.local-login-enabled=true")
            .run(result -> assertThat(result).hasSingleBean(LocalAuthController.class));
    }
}
