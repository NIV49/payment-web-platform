package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.service.AuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BackofficeApiExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BindingController())
        .setControllerAdvice(new BackofficeApiExceptionHandler(mock(AuthenticationService.class)))
        .build();

    @Test
    void missingAndMalformedRequestParametersUseTheStableBadRequestContract() throws Exception {
        mvc.perform(get("/binding"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mvc.perform(get("/binding").queryParam("value", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @RestController
    private static final class BindingController {
        @GetMapping("/binding")
        int binding(@RequestParam int value) {
            return value;
        }
    }
}
