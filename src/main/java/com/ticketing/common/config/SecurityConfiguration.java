package com.ticketing.common.config;

import com.ticketing.identity.BearerTokenFilter;
import com.ticketing.identity.IdentityService;
import com.ticketing.identity.SecurityErrors;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    private static final String[] PUBLIC_READS = { "/api/system/ping", "/actuator/health",
            "/api/venues/{id}", "/api/venues/by-code/{code}", "/api/venues/{id}/areas",
            "/api/venues/{id}/seat-templates", "/api/events/{id}", "/api/events/by-code/{code}",
            "/api/sessions/{id}", "/api/sessions/by-code/{code}", "/api/sessions/{id}/price-tiers",
            "/api/sessions/{id}/seats", "/api/sessions/{id}/seat-map", "/api/sessions/{id}/recommendations",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**" };

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityService identities,
                                                    SecurityErrors errors) throws Exception {
        http.csrf(csrf -> csrf.disable()) // Credentials are explicit Bearer headers; no cookie authentication.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_READS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasRole("CONSUMER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/{id}").hasRole("CONSUMER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/{id}/payments", "/api/orders/{id}/cancel").hasRole("CONSUMER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/{id}/payments", "/api/orders/{id}/tickets").hasRole("CONSUMER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/{orderId}/tickets/{ticketId}/credential",
                                "/api/orders/{orderId}/tickets/{ticketId}/qrcode").hasRole("CONSUMER")
                        .requestMatchers(HttpMethod.GET, "/api/operations/orders/{id}/payment-state",
                                "/api/operations/payments/{number}/notifications").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/operations/orders/{id}/recover",
                                "/api/operations/payments/{number}/succeed", "/api/operations/payments/{number}/fail",
                                "/api/operations/notifications/{number}/deliver").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/venues", "/api/venues/{id}/areas",
                                "/api/areas/{id}/seat-templates", "/api/events", "/api/sessions").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/venues/{id}", "/api/areas/{id}",
                                "/api/seat-templates/{id}", "/api/sessions/{id}/configuration",
                                "/api/sessions/{id}/sales-status", "/api/sessions/{id}/price-tiers/{tierId}/enabled")
                                .hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/seat-templates/{id}").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .addFilterBefore(new BearerTokenFilter(identities, errors), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
