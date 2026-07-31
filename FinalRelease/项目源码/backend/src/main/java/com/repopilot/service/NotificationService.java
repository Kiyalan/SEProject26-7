package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.client.GitHubClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class NotificationService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final JdbcTemplate jdbc;
    private final GitHubClient github;
    private final MailService mailService;

    public NotificationService(JdbcTemplate jdbc, GitHubClient github, MailService mailService) {
        this.jdbc = jdbc;
        this.github = github;
        this.mailService = mailService;
    }

    public Map<String, Object> getSettings(String token) {
        String login = resolveLogin(token);
        ensureRow(login);
        return load(login);
    }

    public Map<String, Object> updateSettings(String token, Map<String, Object> body) {
        String login = resolveLogin(token);
        ensureRow(login);
        Map<String, Object> current = load(login);

        String email = body.containsKey("email")
                ? String.valueOf(body.getOrDefault("email", "")).trim()
                : String.valueOf(current.getOrDefault("email", ""));
        if (!email.isBlank() && !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }

        boolean enabled = bool(body, "enabled", (Boolean) current.get("enabled"));
        boolean onBuild = bool(body, "notifyOnKnowledgeBuild", (Boolean) current.get("notifyOnKnowledgeBuild"));
        boolean onIssue = bool(body, "notifyOnIssueAnalysis", (Boolean) current.get("notifyOnIssueAnalysis"));
        boolean onWiki = bool(body, "notifyOnWikiReady", (Boolean) current.get("notifyOnWikiReady"));
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);

        jdbc.update("""
                UPDATE user_notification_settings
                SET email = ?, enabled = ?, notify_on_knowledge_build = ?,
                    notify_on_issue_analysis = ?, notify_on_wiki_ready = ?, updated_at = ?
                WHERE user_login = ?
                """, email, enabled, onBuild, onIssue, onWiki, now, login);
        return load(login);
    }

    public Map<String, Object> sendTest(String token) {
        String login = resolveLogin(token);
        ensureRow(login);
        Map<String, Object> settings = load(login);
        String email = String.valueOf(settings.getOrDefault("email", "")).trim();
        if (email.isBlank()) {
            throw new IllegalArgumentException("请先填写接收邮箱");
        }
        if (!Boolean.TRUE.equals(settings.get("enabled"))) {
            throw new IllegalArgumentException("请先开启邮件通知");
        }

        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        String deliveryMode;
        String message;

        if (mailService.isConfigured()) {
            mailService.send(email, "[RepoPilot] 测试邮件",
                    "您好，" + login + "：\n\n这是一封来自 RepoPilot 的测试邮件。\n\n您的通知设置已生效，后续将在以下场景收到通知：\n"
                            + "- 知识库构建完成\n- Issue 分析完成\n- Wiki 生成完成\n\n—— RepoPilot 团队");
            deliveryMode = "smtp";
            message = "已向 " + email + " 发送测试邮件";
        } else {
            deliveryMode = "stub";
            message = "【Stub】已模拟向 " + email + " 发送测试通知（SMTP 未配置，请在 application.yml 中配置 repopilot.mail）";
        }

        jdbc.update("""
                UPDATE user_notification_settings
                SET last_test_at = ?, last_test_message = ?, updated_at = ?, delivery_mode = ?
                WHERE user_login = ?
                """, now, message, now, deliveryMode, login);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("deliveryMode", deliveryMode);
        result.put("message", message);
        result.put("sentAt", now);
        result.put("email", email);
        return result;
    }

    /**
     * Send notification if user has enabled it. No-op if not configured or disabled.
     */
    public void notifyIfEnabled(String login, String subject, String body) {
        ensureRow(login);
        Map<String, Object> settings = load(login);
        if (!Boolean.TRUE.equals(settings.get("enabled"))) return;
        String email = String.valueOf(settings.getOrDefault("email", "")).trim();
        if (email.isBlank() || !EMAIL.matcher(email).matches()) return;
        if (!mailService.isConfigured()) return;
        try {
            mailService.send(email, "[RepoPilot] " + subject, body);
        } catch (Exception ignored) {
            // 通知发送失败不影响主流程
        }
    }

    /**
     * Issue analysis digest — requires master switch AND notifyOnIssueAnalysis.
     * @return true if an email was actually sent
     */
    public boolean notifyIssueAnalysis(String login, String subject, String body) {
        ensureRow(login);
        Map<String, Object> settings = load(login);
        if (!Boolean.TRUE.equals(settings.get("enabled"))) return false;
        if (!Boolean.TRUE.equals(settings.get("notifyOnIssueAnalysis"))) return false;
        String email = String.valueOf(settings.getOrDefault("email", "")).trim();
        if (email.isBlank() || !EMAIL.matcher(email).matches()) return false;
        if (!mailService.isConfigured()) return false;
        try {
            mailService.send(email, "[RepoPilot] " + subject, body);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveLogin(String token) {
        JsonNode user = github.get("/user", token);
        String login = user.path("login").asText("").trim();
        if (login.isBlank()) {
            throw new IllegalStateException("无法解析 GitHub 用户");
        }
        return login;
    }

    private void ensureRow(String login) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_notification_settings WHERE user_login = ?",
                Integer.class, login);
        if (count != null && count > 0) {
            return;
        }
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        jdbc.update("""
                INSERT INTO user_notification_settings
                (user_login, email, enabled, notify_on_knowledge_build, notify_on_issue_analysis,
                 notify_on_wiki_ready, delivery_mode, updated_at, last_test_at, last_test_message)
                VALUES (?, '', FALSE, TRUE, FALSE, TRUE, 'stub', ?, '', '')
                """, login, now);
    }

    private Map<String, Object> load(String login) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT email, enabled, notify_on_knowledge_build, notify_on_issue_analysis,
                       notify_on_wiki_ready, delivery_mode, updated_at, last_test_at, last_test_message
                FROM user_notification_settings
                WHERE user_login = ?
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("email", rs.getString("email"));
            row.put("enabled", rs.getBoolean("enabled"));
            row.put("notifyOnKnowledgeBuild", rs.getBoolean("notify_on_knowledge_build"));
            row.put("notifyOnIssueAnalysis", rs.getBoolean("notify_on_issue_analysis"));
            row.put("notifyOnWikiReady", rs.getBoolean("notify_on_wiki_ready"));
            row.put("deliveryMode", rs.getString("delivery_mode"));
            row.put("updatedAt", rs.getString("updated_at"));
            row.put("lastTestAt", rs.getString("last_test_at"));
            row.put("lastTestMessage", rs.getString("last_test_message"));
            return row;
        }, login);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }
}
