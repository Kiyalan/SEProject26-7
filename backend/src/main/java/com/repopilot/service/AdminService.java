package com.repopilot.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public AdminService(JdbcTemplate jdbc, UserService userService) {
        this.jdbc = jdbc;
        this.userService = userService;
    }

    /**
     * 管理员登录 —— 走 app_users 表，不再硬编码。
     */
    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        // 委托 UserService.login() 做用户名/密码/状态校验
        Map<String, Object> result = userService.login(username.trim(), password);
        String role = String.valueOf(result.getOrDefault("role", "user"));
        if (!"admin".equals(role)) {
            throw new IllegalArgumentException("无管理员权限");
        }
        String token = "admin." + UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(
                String.valueOf(result.get("username")), role, System.currentTimeMillis()));
        // 覆盖 token 为 admin session token
        result = new LinkedHashMap<>(result);
        result.put("token", token);
        audit(String.valueOf(result.get("username")), "login", "admin-console", "success");
        return result;
    }

    public String requireAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("需要管理员登录");
        }
        String token = authorization.substring(7).trim();
        Session session = sessions.get(token);
        if (session == null) {
            throw new IllegalArgumentException("管理员会话无效或已过期，请重新登录");
        }
        return session.username();
    }

    public Map<String, Object> overview(String admin) {
        Integer totalRepos = jdbc.queryForObject("SELECT COUNT(*) FROM repo_index", Integer.class);
        Integer synced = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repo_index WHERE status = 'ready'", Integer.class);
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_build_tasks WHERE status = 'failed'", Integer.class);
        Integer chunks = jdbc.queryForObject(
                "SELECT COALESCE(SUM(chunk_count), 0) FROM repo_index", Integer.class);
        Integer faq = jdbc.queryForObject("SELECT COUNT(*) FROM repo_faq_items", Integer.class);
        Integer tasks = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_build_tasks", Integer.class);
        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_build_tasks WHERE status = 'completed'", Integer.class);
        double rate = (tasks == null || tasks == 0) ? 0
                : Math.round((completed == null ? 0 : completed) * 1000.0 / tasks) / 10.0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRepos", totalRepos == null ? 0 : totalRepos);
        stats.put("syncedRepos", synced == null ? 0 : synced);
        stats.put("failedRepos", failed == null ? 0 : failed);
        stats.put("knowledgeChunks", chunks == null ? 0 : chunks);
        stats.put("faqEntries", faq == null ? 0 : faq);
        stats.put("activeUsers", jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE status = 'active'", Integer.class));
        stats.put("syncSuccessRate", rate);
        String lastIndexed = jdbc.query("""
                SELECT MAX(indexed_at) FROM repo_index WHERE indexed_at IS NOT NULL AND indexed_at <> ''
                """, rs -> rs.next() ? rs.getString(1) : null);
        stats.put("lastIndexedAt", lastIndexed == null || lastIndexed.isBlank() ? "" : lastIndexed);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(i);
            String prefix = day.toString();
            Integer ok = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_build_tasks WHERE status='completed' AND requested_at LIKE ?",
                    Integer.class, prefix + "%");
            Integer bad = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM knowledge_build_tasks WHERE status='failed' AND requested_at LIKE ?",
                    Integer.class, prefix + "%");
            trend.add(Map.of(
                    "date", prefix,
                    "success", ok == null ? 0 : ok,
                    "failed", bad == null ? 0 : bad
            ));
        }

        audit(admin, "view_overview", "dashboard", "success");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) listSyncTasks(null, null, 5).get("items");
        return Map.of(
                "stats", stats,
                "healthTrend", trend,
                "recentSyncTasks", recent
        );
    }

    public Map<String, Object> listSyncTasks(String status, String keyword, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        StringBuilder sql = new StringBuilder("""
                SELECT t.task_id, t.repo_id, COALESCE(r.full_name, t.repo_id) AS full_name,
                       t.status, t.requested_at, t.finished_at, t.files_indexed, t.message
                FROM knowledge_build_tasks t
                LEFT JOIN repo_index r ON r.repo_id = t.repo_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            String mapped = mapStatusFilter(status);
            sql.append(" AND t.status = ?");
            args.add(mapped);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(COALESCE(r.full_name, '')) LIKE ? OR LOWER(t.task_id) LIKE ? OR LOWER(t.repo_id) LIKE ?)");
            String like = "%" + keyword.toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY t.requested_at DESC LIMIT ?");
        args.add(safeLimit);

        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("task_id"));
            row.put("repoId", rs.getString("repo_id"));
            row.put("repoFullName", rs.getString("full_name"));
            row.put("owner", ownerOf(rs.getString("full_name")));
            row.put("status", mapTaskStatus(rs.getString("status")));
            row.put("startedAt", rs.getString("requested_at"));
            row.put("endedAt", rs.getString("finished_at"));
            row.put("filesSynced", rs.getInt("files_indexed"));
            String message = rs.getString("message");
            row.put("errorMessage", "failed".equals(rs.getString("status")) ? message : null);
            return row;
        }, args.toArray());
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> listSyncFailures(int limit) {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT t.task_id, t.repo_id, COALESCE(r.full_name, t.repo_id) AS full_name,
                       t.finished_at, t.message
                FROM knowledge_build_tasks t
                LEFT JOIN repo_index r ON r.repo_id = t.repo_id
                WHERE t.status = 'failed'
                ORDER BY CASE WHEN t.finished_at IS NULL THEN 1 ELSE 0 END, t.finished_at DESC, t.requested_at DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            String message = rs.getString("message");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("task_id"));
            row.put("repoFullName", rs.getString("full_name"));
            row.put("failedAt", rs.getString("finished_at"));
            row.put("errorType", classifyErrorType(message));
            row.put("errorMessage", message == null ? "" : message);
            return row;
        }, Math.min(Math.max(limit, 1), 200));
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> integrity(int limit) {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT r.repo_id, r.full_name, r.status, r.chunk_count, r.indexed_at,
                       (SELECT COUNT(*) FROM repo_faq_items f WHERE f.repo_id = r.repo_id) AS faq_count
                FROM repo_index r
                ORDER BY r.indexed_at DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            boolean knowledgeOk = "ready".equals(rs.getString("status"));
            int faqCount = rs.getInt("faq_count");
            List<String> issues = new ArrayList<>();
            if (!knowledgeOk) issues.add("知识库未就绪");
            if (faqCount == 0) issues.add("尚未生成 FAQ");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repoId", rs.getString("repo_id"));
            row.put("repoFullName", rs.getString("full_name"));
            row.put("knowledgeOk", knowledgeOk);
            row.put("faqOk", faqCount > 0);
            row.put("chunkCount", rs.getInt("chunk_count"));
            row.put("lastChecked", rs.getString("indexed_at"));
            row.put("issues", issues);
            return row;
        }, Math.min(Math.max(limit, 1), 200));
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> faqRepos() {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT r.repo_id, r.full_name,
                       (SELECT COUNT(*) FROM repo_faq_items f WHERE f.repo_id = r.repo_id) AS faq_count,
                       (SELECT MAX(updated_at) FROM repo_faq_items f WHERE f.repo_id = r.repo_id) AS last_updated
                FROM repo_index r
                ORDER BY r.full_name
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repoId", rs.getString("repo_id"));
            row.put("repoFullName", rs.getString("full_name"));
            row.put("faqCount", rs.getInt("faq_count"));
            row.put("lastUpdated", rs.getString("last_updated") == null ? "" : rs.getString("last_updated"));
            return row;
        });
        return Map.of("items", items, "total", items.size());
    }
    public Map<String, Object> exportFaq(List<String> repoIds, String format, String admin) {
        String normalized = "json".equalsIgnoreCase(format) ? "json" : "markdown";
        String exportedAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        List<String> ids = repoIds == null ? List.of() : repoIds;
        StringBuilder markdown = new StringBuilder("# RepoPilot FAQ 导出\n\n导出时间: ").append(exportedAt).append("\n\n");
        List<Map<String, Object>> jsonRepos = new ArrayList<>();
        int totalItems = 0;

        for (String repoId : ids) {
            List<Map<String, Object>> faqs = jdbc.query("""
                    SELECT category, question, answer, updated_at
                    FROM repo_faq_items WHERE repo_id = ? ORDER BY category
                    """, (rs, rowNum) -> Map.of(
                    "category", rs.getString("category"),
                    "question", rs.getString("question"),
                    "answer", rs.getString("answer"),
                    "updatedAt", rs.getString("updated_at")
            ), repoId);
            String fullName = jdbc.query("""
                    SELECT full_name FROM repo_index WHERE repo_id = ?
                    """, rs -> rs.next() ? rs.getString(1) : repoId, repoId);
            totalItems += faqs.size();
            markdown.append("## ").append(fullName).append("\n\n");
            if (faqs.isEmpty()) {
                markdown.append("_暂无 FAQ_\n\n");
            } else {
                for (Map<String, Object> faq : faqs) {
                    markdown.append("### ").append(faq.get("question")).append("\n");
                    markdown.append(faq.get("answer")).append("\n\n");
                }
            }
            jsonRepos.add(Map.of("repoId", repoId, "fullName", fullName, "items", faqs));
        }

        String content = "json".equals(normalized)
                ? toJson(Map.of("exportedAt", exportedAt, "repos", jsonRepos))
                : markdown.toString();
        audit(admin, "export_faq", String.join(",", ids), "success");
        return Map.of(
                "format", normalized,
                "content", content,
                "itemCount", totalItems,
                "repoCount", ids.size(),
                "exportedAt", exportedAt
        );
    }

    public Map<String, Object> auditLogs(int limit) {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT id, admin_name, action, target, result, created_at
                FROM admin_audit_logs
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("admin", rs.getString("admin_name"));
            row.put("action", rs.getString("action"));
            row.put("target", rs.getString("target"));
            row.put("result", rs.getString("result"));
            row.put("createdAt", rs.getString("created_at"));
            return row;
        }, Math.min(Math.max(limit, 1), 200));
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> users() {
        List<Map<String, Object>> items = userService.listAll();
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> createUser(String admin, String username, String password, String email, String role) {
        Map<String, Object> user = userService.createUser(username, password, email, role);
        audit(admin, "create_user", username, "success");
        return user;
    }

    public Map<String, Object> updateUser(String admin, Long id, String password, String email, String role, String status) {
        Map<String, Object> user = userService.updateUser(id, password, email, role, status);
        audit(admin, "update_user", String.valueOf(id), "success");
        return user;
    }

    public void deleteUser(String admin, Long id) {
        userService.deleteUser(id);
        audit(admin, "delete_user", String.valueOf(id), "success");
    }

    // ── 封禁 / 解禁 ───────────────────────────────────

    public Map<String, Object> banUser(String admin, Long id) {
        Map<String, Object> user = userService.banUser(id);
        audit(admin, "ban_user", String.valueOf(id), "success");
        return user;
    }

    public Map<String, Object> unbanUser(String admin, Long id) {
        Map<String, Object> user = userService.unbanUser(id);
        audit(admin, "unban_user", String.valueOf(id), "success");
        return user;
    }

    // ── 用户统计 ───────────────────────────────────────

    public Map<String, Object> globalUserStats() {
        return userService.getGlobalUserStats();
    }

    public Map<String, Object> userStats(String admin, Long id) {
        var user = userService.listAll().stream()
                .filter(u -> u.get("id").equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        // listAll 中已经包含了统计信息，直接返回
        return user;
    }

    public void audit(String admin, String action, String target, String result) {
        jdbc.update("""
                INSERT INTO admin_audit_logs (id, admin_name, action, target, result, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                admin,
                action,
                target == null ? "" : target,
                result,
                LocalDateTime.now(ZoneOffset.UTC).format(TS));
    }

    private static String classifyErrorType(String message) {
        String m = message == null ? "" : message.toLowerCase();
        if (m.contains("401") || m.contains("403") || m.contains("auth") || m.contains("token") || m.contains("unauthorized")) {
            return "auth";
        }
        if (m.contains("rate limit") || m.contains("429") || m.contains("secondary rate")) {
            return "rate_limit";
        }
        if (m.contains("timeout") || m.contains("timed out") || m.contains("connection")
                || m.contains("connect") || m.contains("network") || m.contains("unreachable")) {
            return "network";
        }
        if (m.contains("webhook")) {
            return "webhook";
        }
        return "parse";
    }

    private static String mapTaskStatus(String status) {
        return switch (status == null ? "" : status) {
            case "completed" -> "success";
            case "running", "queued" -> "running";
            case "failed" -> "failed";
            case "cancelled" -> "paused";
            default -> "paused";
        };
    }

    private static String mapStatusFilter(String status) {
        return switch (status) {
            case "success" -> "completed";
            case "running" -> "running";
            case "failed" -> "failed";
            case "paused" -> "cancelled";
            default -> status;
        };
    }

    private static String ownerOf(String fullName) {
        if (fullName == null || !fullName.contains("/")) return "";
        return fullName.substring(0, fullName.indexOf('/'));
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private record Session(String username, String role, long createdAt) {}
}
