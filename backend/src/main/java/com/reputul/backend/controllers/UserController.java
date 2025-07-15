package com.reputul.backend.controllers;

import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepo;

    public UserController(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @GetMapping
    public List<User> getAllUsers() {
        return userRepo.findAll();
    }

    @PostMapping
    public User createUser(@RequestBody User user) {
        System.out.println("Received user: " + user);
        return userRepo.save(user);
    }

    @GetMapping("/ping")
    public String ping() {
        System.out.println("Ping received");
        return "pong";
    }
}
