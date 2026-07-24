package com.repopilot.controller;

import com.repopilot.security.AuthSupport;
import com.repopilot.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user/setting/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    Map<String, Object> get(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        return notificationService.getSettings(token);
    }

    @PutMapping
    Map<String, Object> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body
    ) {
        String token = AuthSupport.requireToken(authorization);
        return notificationService.updateSettings(token, body);
    }

    @PostMapping("/test")
    Map<String, Object> test(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        String token = AuthSupport.requireToken(authorization);
        return notificationService.sendTest(token);
    }
}
