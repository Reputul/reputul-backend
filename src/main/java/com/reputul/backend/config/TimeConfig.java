package com.reputul.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a single source of truth for time across the app.
 * Always use Clock injection instead of LocalDate.now() / OffsetDateTime.now(ZoneOffset.UTC).
 */
@Configuration
public class TimeConfig {
    @Bean
    public Clock appClock() {
        return Clock.systemUTC();
    }
}
