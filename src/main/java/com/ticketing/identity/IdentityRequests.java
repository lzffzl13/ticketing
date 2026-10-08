package com.ticketing.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class IdentityRequests {
    private IdentityRequests() { }

    public record Login(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String loginName,
                        @NotBlank @Size(max = 72) String password) { }
}
