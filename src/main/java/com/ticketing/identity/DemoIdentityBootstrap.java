package com.ticketing.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DemoIdentityBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final DemoAccountSeeder seeder;

    public DemoIdentityBootstrap(Environment environment, DemoAccountSeeder seeder) {
        this.environment = environment;
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = environment.getProperty("ticketing.demo-password", environment.getProperty("DEMO_PASSWORD"));
        if (password != null && !password.isBlank()) { seeder.seed(password); }
    }
}
