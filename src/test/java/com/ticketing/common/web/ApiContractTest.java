package com.ticketing.common.web;

import com.ticketing.common.config.SecurityConfiguration;
import com.ticketing.identity.IdentityController;
import com.ticketing.identity.IdentityModels.AuthenticatedAccount;
import com.ticketing.identity.IdentityModels.LoginResult;
import com.ticketing.identity.IdentityModels.Role;
import com.ticketing.identity.IdentityRequests.Login;
import com.ticketing.identity.IdentityService;
import com.ticketing.identity.SecurityErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import({ApiContractTest.FixtureController.class, ApiExceptionHandler.class, RequestIdFilter.class,
        SecurityConfiguration.class, SecurityErrors.class, IdentityController.class})
class ApiContractTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean private IdentityService identities;
    private String operatorToken;

    @BeforeEach
    void loginThroughTheSecurityEntryPoint() throws Exception {
        var operator = new AuthenticatedAccount(1L, "operator1", Role.OPERATOR);
        when(identities.login(any(Login.class))).thenReturn(new LoginResult("slice-operator-token",
                Instant.parse("2027-01-01T02:00:00Z"), operator));
        when(identities.authenticate("slice-operator-token")).thenReturn(operator);
        var result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"operator1\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn();
        operatorToken = "Bearer " + new tools.jackson.databind.json.JsonMapper()
                .readTree(result.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    @Test
    void successIncludesTheSameRequestIdInHeaderAndBody() throws Exception {
        var result = mvc.perform(get("/api/system/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("[a-f0-9-]{36}")))
                .andReturn();
        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(requestId);
        jsonPath("$.requestId").value(requestId).match(result);
        assertNull(org.slf4j.MDC.get("requestId"));
        mvc.perform(get("/api/system/ping")).andExpect(header().string("X-Request-Id", not(requestId)));
    }

    @Test
    void invalidFieldsReturn400AndFieldDetails() throws Exception {
        mvc.perform(post("/api/venues").header("Authorization", operatorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data[0].field").value("name"));
    }

    @Test
    void malformedJsonReturns400InsteadOf500() throws Exception {
        mvc.perform(post("/api/venues").header("Authorization", operatorToken).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void businessConflictKeepsItsCodeAndHttpStatus() throws Exception {
        mvc.perform(get("/api/venues/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_CONFLICT"));
    }

    @Test
    void unknownPathsAreDeniedByTheRealSecurityChain() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/missing").header("Authorization", operatorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unexpectedFailureReturns500WithoutInternalMessage() throws Exception {
        mvc.perform(get("/api/venues/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("internal-test-detail"))));
    }

    @Test
    void frameworkServerErrorsKeepTheirStatusAndUseTheInternalErrorCode() throws Exception {
        mvc.perform(get("/api/venues/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class FixtureController {
        @PostMapping("/api/venues")
        ApiResponse<Input> validation(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }

        @GetMapping("/api/venues/conflict")
        ApiResponse<Void> conflict() {
            throw new BusinessException("TEST_CONFLICT", "测试冲突", HttpStatus.CONFLICT);
        }

        @GetMapping("/api/venues/failure")
        ApiResponse<Void> failure() {
            throw new IllegalStateException("internal-test-detail");
        }

        @GetMapping("/api/venues/unavailable")
        ApiResponse<Void> unavailable() {
            throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
        }
    }

    record Input(@NotBlank String name) {
    }

}
