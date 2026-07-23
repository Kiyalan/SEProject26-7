package com.repopilot.controller;

import com.repopilot.service.AdminService;
import com.repopilot.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final UserService userService;

    public AdminController(AdminService adminService, UserService userService) {
        this.adminService = adminService;
        this.userService = userService;
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody Map<String, Object> body) {
        try {
            return adminService.login(
                    String.valueOf(body.getOrDefault("username", "")),
                    String.valueOf(body.getOrDefault("password", ""))
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }

    @GetMapping("/overview")
    Map<String, Object> overview(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String admin = authorize(authorization);
        return adminService.overview(admin);
    }

    @GetMapping("/sync-tasks")
    Map<String, Object> syncTasks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(defaultValue = "50") int limit
    ) {
        authorize(authorization);
        return adminService.listSyncTasks(status, keyword, limit);
    }

    @GetMapping("/sync-failures")
    Map<String, Object> syncFailures(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "50") int limit
    ) {
        authorize(authorization);
        return adminService.listSyncFailures(limit);
    }

    @GetMapping("/integrity")
    Map<String, Object> integrity(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "100") int limit
    ) {
        authorize(authorization);
        return adminService.integrity(limit);
    }

    @GetMapping("/faq/repos")
    Map<String, Object> faqRepos(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(authorization);
        return adminService.faqRepos();
    }

    @PostMapping("/faq/export")
    Map<String, Object> exportFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body
    ) {
        String admin = authorize(authorization);
        @SuppressWarnings("unchecked")
        List<String> repoIds = body.get("repoIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        String format = String.valueOf(body.getOrDefault("format", "markdown"));
        return adminService.exportFaq(repoIds, format, admin);
    }

    @GetMapping("/audit-logs")
    Map<String, Object> auditLogs(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "100") int limit
    ) {
        authorize(authorization);
        return adminService.auditLogs(limit);
    }

    @GetMapping("/users")
    Map<String, Object> users(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(authorization);
        return adminService.users();
    }

    @PostMapping("/users")
    Map<String, Object> createUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body
    ) {
        String admin = authorize(authorization);
        String username = String.valueOf(body.getOrDefault("username", ""));
        String password = String.valueOf(body.getOrDefault("password", ""));
        String email = String.valueOf(body.getOrDefault("email", ""));
        String role = String.valueOf(body.getOrDefault("role", "user"));
        try {
            return adminService.createUser(admin, username, password, email, role);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/users/{id}")
    Map<String, Object> updateUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        String admin = authorize(authorization);
        String password = body.containsKey("password") ? String.valueOf(body.get("password")) : null;
        String email = body.containsKey("email") ? String.valueOf(body.get("email")) : null;
        String role = body.containsKey("role") ? String.valueOf(body.get("role")) : null;
        String status = body.containsKey("status") ? String.valueOf(body.get("status")) : null;
        try {
            return adminService.updateUser(admin, id, password, email, role, status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/users/{id}")
    Map<String, Object> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id
    ) {
        String admin = authorize(authorization);
        try {
            adminService.deleteUser(admin, id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return Map.of("success", true);
    }

    private String authorize(String authorization) {
        try {
            return adminService.requireAdmin(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }
}
