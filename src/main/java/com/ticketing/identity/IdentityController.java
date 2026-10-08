package com.ticketing.identity;

import com.ticketing.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class IdentityController {
    private final IdentityService identities;

    public IdentityController(IdentityService identities) { this.identities = identities; }

    @PostMapping("/login")
    public ApiResponse<IdentityModels.LoginResult> login(@Valid @RequestBody IdentityRequests.Login request) {
        return ApiResponse.success(identities.login(request));
    }
}
