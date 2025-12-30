package com.reputul.backend;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12); // Your strength!

        String password = "password";
        String hash = encoder.encode(password);

        System.out.println("Password: " + password);
        System.out.println("Hash: " + hash);
    }
}
