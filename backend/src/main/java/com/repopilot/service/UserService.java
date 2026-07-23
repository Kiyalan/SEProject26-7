package com.repopilot.service;

import com.repopilot.entity.UserEntity;
import com.repopilot.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class UserService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserRepository userRepo;

    public UserService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserEntity u : userRepo.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("login", u.getUsername());
            row.put("username", u.getUsername());
            row.put("email", u.getEmail());
            row.put("role", u.getRole());
            row.put("status", u.getStatus());
            row.put("boundRepos", 0);
            row.put("createdAt", u.getCreatedAt());
            row.put("lastLogin", u.getLastLoginAt());
            result.add(row);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createUser(String username, String password, String email, String role) {
        if (username == null || username.trim().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.trim().length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (userRepo.existsByUsername(username.trim())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username.trim());
        user.setPasswordHash(hash(password.trim()));
        user.setEmail(email == null ? "" : email.trim());
        user.setRole(validRole(role));
        user.setStatus("active");
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        user.setCreatedAt(now);
        user.setLastLoginAt("");
        userRepo.save(user);
        return toMap(user);
    }

    @Transactional
    public Map<String, Object> updateUser(Long id, String password, String email, String role, String status) {
        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (password != null && !password.trim().isBlank()) {
            if (password.trim().length() < 6) {
                throw new IllegalArgumentException("密码至少 6 位");
            }
            user.setPasswordHash(hash(password.trim()));
        }
        if (email != null) user.setEmail(email.trim());
        if (role != null) user.setRole(validRole(role));
        if (status != null) user.setStatus(validStatus(status));
        userRepo.save(user);
        return toMap(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        userRepo.delete(user);
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        UserEntity user = userRepo.findByUsername(username.trim())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        if (!user.getPasswordHash().equals(hash(password))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
        userRepo.save(user);
        String token = "user." + UUID.randomUUID().toString().replace("-", "");
        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole()
        );
    }

    @Transactional
    public void ensureDefaultAdmin() {
        if (!userRepo.existsByUsername("admin")) {
            UserEntity admin = new UserEntity();
            admin.setUsername("admin");
            admin.setPasswordHash(hash("repopilot2026"));
            admin.setEmail("");
            admin.setRole("admin");
            admin.setStatus("active");
            admin.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
            userRepo.save(admin);
        }
    }

    private static String hash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    private static String validRole(String role) {
        return Set.of("admin", "user", "viewer").contains(role) ? role : "user";
    }

    private static String validStatus(String status) {
        return Set.of("active", "disabled").contains(status) ? status : "active";
    }

    private static Map<String, Object> toMap(UserEntity u) {
        return Map.<String, Object>of(
                "id", u.getId(),
                "login", u.getUsername(),
                "username", u.getUsername(),
                "email", u.getEmail(),
                "role", u.getRole(),
                "status", u.getStatus(),
                "boundRepos", 0,
                "createdAt", u.getCreatedAt(),
                "lastLogin", u.getLastLoginAt()
        );
    }
}
