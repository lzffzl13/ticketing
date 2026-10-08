package com.ticketing.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Registered only inside SecurityFilterChain, never as a second servlet filter. */
public class BearerTokenFilter extends OncePerRequestFilter {
    private final IdentityService identities;
    private final SecurityErrors errors;

    public BearerTokenFilter(IdentityService identities, SecurityErrors errors) {
        this.identities = identities;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (headers.isEmpty()) { filterChain.doFilter(request, response); return; }
        try {
            String header = headers.getFirst();
            if (headers.size() != 1 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new BadCredentialsException("Bearer header required");
            }
            var account = identities.authenticate(header.substring(7));
            var authentication = UsernamePasswordAuthenticationToken.authenticated(account, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            errors.commence(request, response, exception);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
