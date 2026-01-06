package com.reputul.backend;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class BackendApplication {

	@PostConstruct
	public void checkActiveProfile() {
		System.out.println("✅ ACTIVE PROFILE: " + System.getProperty("spring.profiles.active"));
		System.out.println("✅ SPRING_PROFILES_ACTIVE: " + System.getenv("SPRING_PROFILES_ACTIVE"));
	}
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}
}
