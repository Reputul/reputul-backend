package com.reputul.backend.controllers;

import com.reputul.backend.dto.UserProfileDto;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    // Create user (for manual testing or seeding)
    @PostMapping
    public User createUser(@RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepo.save(user);
    }

    // Admin-only: Get all users (optional)
    @GetMapping
    public List<User> getAllUsers() {
        return userRepo.findAll();
    }

    // ✅ GET /api/users/profile
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        return userRepo.findByEmail(userDetails.getUsername())
                .map(user -> {
                    UserProfileDto response = new UserProfileDto(
                            user.getId(),
                            user.getName(),
                            user.getEmail()
                    );
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }


    // ✅ PUT /api/users/profile — allow update of name or password
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@AuthenticationPrincipal UserDetails userDetails,
                                           @RequestBody User updatedUser) {
        return userRepo.findByEmail(userDetails.getUsername())
                .map(user -> {
                    if (updatedUser.getName() != null && !updatedUser.getName().isBlank()) {
                        user.setName(updatedUser.getName());
                    }

                    if (updatedUser.getPassword() != null && !updatedUser.getPassword().isBlank()) {
                        user.setPassword(passwordEncoder.encode(updatedUser.getPassword()));
                    }

                    userRepo.save(user);

                    UserProfileDto response = new UserProfileDto(user.getId(), user.getName(), user.getEmail());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

}
