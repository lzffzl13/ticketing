package com.ticketing.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
        return new OpenAPI().info(new Info()
                .title("座位票务与现场履约系统")
                .version("0.0.1")
                .description("工程骨架；业务接口按项目计划逐步增加"));
    }
}
