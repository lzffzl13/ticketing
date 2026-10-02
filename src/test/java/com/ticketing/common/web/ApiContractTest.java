package com.ticketing.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import({ApiContractTest.FixtureController.class, ApiExceptionHandler.class, RequestIdFilter.class})
class ApiContractTest {
    @Autowired
    private MockMvc mvc;

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
        mvc.perform(post("/test/validation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data[0].field").value("name"));
    }

    @Test
    void malformedJsonReturns400InsteadOf500() throws Exception {
        mvc.perform(post("/test/validation").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void businessConflictKeepsItsCodeAndHttpStatus() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_CONFLICT"));
    }

    @Test
    void unknownPathsKeep404() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedFailureReturns500WithoutInternalMessage() throws Exception {
        mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("internal-test-detail"))));
    }

    @Test
    void frameworkServerErrorsKeepTheirStatusAndUseTheInternalErrorCode() throws Exception {
        mvc.perform(get("/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class FixtureController {
        @PostMapping("/test/validation")
        ApiResponse<Input> validation(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }

        @GetMapping("/test/conflict")
        ApiResponse<Void> conflict() {
            throw new BusinessException("TEST_CONFLICT", "测试冲突", HttpStatus.CONFLICT);
        }

        @GetMapping("/test/failure")
        ApiResponse<Void> failure() {
            throw new IllegalStateException("internal-test-detail");
        }

        @GetMapping("/test/unavailable")
        ApiResponse<Void> unavailable() {
            throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
        }
    }

    record Input(@NotBlank String name) {
    }
}
