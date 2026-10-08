package com.ticketing.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ApplicationConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("POST /api/auth/login 返回的不透明令牌")))
                .info(new Info()
                .title("座位票务与现场履约系统")
                .version("0.0.1")
                .description("场馆与场次、座位图与推荐、固定账号登录、幂等下单与整组锁座"));
    }
}
