package com.reputul.backend.controllers;

import com.reputul.backend.models.Notification;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.NotificationRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepo;
    private final UserRepository userRepo;

    public NotificationController(NotificationRepository notificationRepo, UserRepository userRepo) {
        this.notificationRepo = notificationRepo;
        this.userRepo = userRepo;
    }

    @PostMapping("/{userId}")
    public Notification create(@PathVariable Long userId, @RequestBody Notification notification) {
        User user = userRepo.findById(userId).orElseThrow();
        notification.setUser(user);
        notification.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        notification.setRead(false);
        return notificationRepo.save(notification);
    }

    @GetMapping("/user/{userId}")
    public List<Notification> getByUser(@PathVariable Long userId) {
        return notificationRepo.findByUserId(userId);
    }

    @PutMapping("/{id}/read")
    public Notification markAsRead(@PathVariable Long id) {
        Notification n = notificationRepo.findById(id).orElseThrow();
        n.setRead(true);
        return notificationRepo.save(n);
    }
}
