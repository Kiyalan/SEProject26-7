package com.repopilot.service;

import com.repopilot.entity.UserEntity;
import com.repopilot.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public UserService(UserRepository userRepo, JdbcTemplate jdbc) {
        this.userRepo = userRepo;
        this.jdbc = jdbc;
    }

    // ── 列表 ──────────────────────────────────────────

    /**
     * 管理员面板用户列表（含实时统计）
     */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserEntity u : userRepo.findAll()) {
            String ownerLogin = u.getGithubLogin().isBlank() ? u.getUsername() : u.getGithubLogin();
            Map<String, Object> stats = computeUserStats(ownerLogin);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("login", u.getUsername());
            row.put("username", u.getUsername());
            row.put("email", u.getEmail());
            row.put("role", u.getRole());
            row.put("status", u.getStatus());
            row.put("avatarUrl", u.getAvatarUrl());
            row.put("githubLogin", u.getGithubLogin());
            row.put("boundRepos", stats.getOrDefault("reposIndexed", 0));
            row.put("buildTasksCompleted", stats.getOrDefault("buildTasksCompleted", 0));
            row.put("buildTasksFailed", stats.getOrDefault("buildTasksFailed", 0));
            row.put("createdAt", u.getCreatedAt());
            row.put("lastLogin", u.getLastLoginAt());
            row.put("lastActive", stats.getOrDefault("lastActive", ""));
            result.add(row);
        }
        return result;
    }

    // ── GitHub OAuth 自动建用户 ────────────────────────

    /**
     * GitHub OAuth 回调后调用：自动创建或更新用户记录。
     * 如果用户状态为 disabled，抛出异常阻止登录。
     */
    @Transactional
    public UserEntity findOrCreateByGithubLogin(String githubLogin, String avatarUrl) {
        String trimmed = githubLogin.trim();
        Optional<UserEntity> opt = userRepo.findByUsername(trimmed);
        UserEntity user;
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);

        if (opt.isPresent()) {
            user = opt.get();
            // 更新 GitHub 信息
            user.setGithubLogin(trimmed);
            if (avatarUrl != null && !avatarUrl.isBlank()) {
                user.setAvatarUrl(avatarUrl);
            }
            user.setLastLoginAt(now);
            user = userRepo.save(user);
        } else {
            user = new UserEntity();
            user.setUsername(trimmed);
            user.setGithubLogin(trimmed);
            user.setPasswordHash(""); // GitHub 用户无密码
            user.setEmail("");
            user.setRole("user");
            user.setStatus("active");
            user.setAvatarUrl(avatarUrl != null ? avatarUrl : "");
            user.setCreatedAt(now);
            user.setLastLoginAt(now);
            user = userRepo.save(user);
        }

        if ("disabled".equals(user.getStatus())) {
            throw new IllegalArgumentException("您的账号已被管理员禁用，无法登录");
        }
        return user;
    }

    // ── CRUD ───────────────────────────────────────────

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

    // ── 登录（管理员用，查 app_users 表） ──────────────

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
        String token = "admin." + UUID.randomUUID().toString().replace("-", "");
        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole()
        );
    }

    // ── 封禁 / 解禁 ───────────────────────────────────

    @Transactional
    public Map<String, Object> banUser(Long id) {
        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if ("admin".equals(user.getRole())) {
            throw new IllegalArgumentException("不能封禁管理员");
        }
        user.setStatus("disabled");
        userRepo.save(user);
        return toMap(user);
    }

    @Transactional
    public Map<String, Object> unbanUser(Long id) {
        UserEntity user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setStatus("active");
        userRepo.save(user);
        return toMap(user);
    }

    // ── 统计 ───────────────────────────────────────────

    /**
     * 全局用户统计
     */
    public Map<String, Object> getGlobalUserStats() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE status='active'", Integer.class);
        Integer disabled = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE status='disabled'", Integer.class);
        Integer adminCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='admin'", Integer.class);
        Integer totalRepos = jdbc.queryForObject("SELECT COUNT(*) FROM repo_index", Integer.class);
        Integer totalBuilds = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_build_tasks", Integer.class);
        Integer activeUsers7d = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT owner_login) FROM knowledge_build_tasks WHERE requested_at >= ?",
                Integer.class,
                LocalDateTime.now(ZoneOffset.UTC).minusDays(7).format(TS));

        return Map.of(
                "totalUsers", total == null ? 0 : total,
                "activeUsers", active == null ? 0 : active,
                "disabledUsers", disabled == null ? 0 : disabled,
                "adminCount", adminCount == null ? 0 : adminCount,
                "totalRepos", totalRepos == null ? 0 : totalRepos,
                "totalBuildTasks", totalBuilds == null ? 0 : totalBuilds,
                "activeUsers7d", activeUsers7d == null ? 0 : activeUsers7d
        );
    }

    /**
     * 根据 ownerLogin 计算用户使用统计
     */
    public Map<String, Object> computeUserStats(String ownerLogin) {
        Integer repos = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repo_index WHERE owner_login = ?", Integer.class, ownerLogin);
        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_build_tasks WHERE owner_login = ? AND status = 'completed'",
                Integer.class, ownerLogin);
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_build_tasks WHERE owner_login = ? AND status = 'failed'",
                Integer.class, ownerLogin);
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_build_tasks WHERE owner_login = ? AND status = 'running'",
                Integer.class, ownerLogin);
        Integer faqCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repo_faq_items WHERE owner_login = ?", Integer.class, ownerLogin);
        String lastActive = jdbc.queryForObject(
                "SELECT MAX(requested_at) FROM knowledge_build_tasks WHERE owner_login = ?",
                String.class, ownerLogin);

        return Map.of(
                "reposIndexed", repos == null ? 0 : repos,
                "buildTasksCompleted", completed == null ? 0 : completed,
                "buildTasksFailed", failed == null ? 0 : failed,
                "buildTasksRunning", running == null ? 0 : running,
                "faqCount", faqCount == null ? 0 : faqCount,
                "lastActive", lastActive == null ? "" : lastActive
        );
    }

    // ── 初始化默认管理员 ────────────────────────────────

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

    // ── 工具方法 ───────────────────────────────────────

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

    private Map<String, Object> toMap(UserEntity u) {
        String ownerLogin = u.getGithubLogin().isBlank() ? u.getUsername() : u.getGithubLogin();
        Map<String, Object> stats = computeUserStats(ownerLogin);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", u.getId());
        map.put("login", u.getUsername());
        map.put("username", u.getUsername());
        map.put("email", u.getEmail());
        map.put("role", u.getRole());
        map.put("status", u.getStatus());
        map.put("avatarUrl", u.getAvatarUrl());
        map.put("githubLogin", u.getGithubLogin());
        map.put("boundRepos", stats.getOrDefault("reposIndexed", 0));
        map.put("buildTasksCompleted", stats.getOrDefault("buildTasksCompleted", 0));
        map.put("buildTasksFailed", stats.getOrDefault("buildTasksFailed", 0));
        map.put("lastActive", stats.getOrDefault("lastActive", ""));
        map.put("createdAt", u.getCreatedAt());
        map.put("lastLogin", u.getLastLoginAt());
        return map;
    }
}
