package com.reputul.backend.controllers;

import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
    @PostMapping("/api/users")
    public User createUser(@RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepo.save(user);
    }

    @GetMapping
    public List<User> getAllUsers() {
        return userRepo.findAll();
    }

}
