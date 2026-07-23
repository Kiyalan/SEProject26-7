package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.util.JsonUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeBuildTaskService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public KnowledgeBuildTaskService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        List<String> interrupted = jdbc.queryForList("""
                SELECT task_id FROM knowledge_build_tasks
                WHERE status IN ('queued', 'running')
                """, String.class);
        for (String taskId : interrupted) {
            insertError(taskId, "recovery", "", "PROCESS_RESTARTED",
                    "后端进程重启，任务未能继续执行", true);
        }
        if (!interrupted.isEmpty()) {
            jdbc.update("""
                    UPDATE knowledge_build_tasks
                    SET status = 'failed', finished_at = ?, message = '后端进程重启，任务已中断',
                        quality_status = 'failed', quality_score = 0
                    WHERE status IN ('queued', 'running')
                    """, now());
        }
    }

    public String create(String repoId, String mode) {
        String taskId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO knowledge_build_tasks
                    (task_id, repo_id, status, mode, requested_at, message)
                VALUES (?, ?, 'queued', ?, ?, '等待构建')
                """, taskId, repoId, mode, now());
        return taskId;
    }

    public void start(String taskId) {
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET status = 'running', started_at = ?, message = '准备构建知识库'
                WHERE task_id = ?
                """, now(), taskId);
    }

    public void setCommits(String taskId, String baseSha, String targetSha) {
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET base_commit_sha = ?, target_commit_sha = ?
                WHERE task_id = ?
                """, safe(baseSha), safe(targetSha), taskId);
    }

    public void progress(String taskId, int done, int total, String message) {
        int safeTotal = Math.max(total, 1);
        int safeDone = Math.min(Math.max(done, 0), safeTotal);
        double percent = Math.round(safeDone * 1000.0 / safeTotal) / 10.0;
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET completed_steps = ?, total_steps = ?, progress = ?, message = ?
                WHERE task_id = ?
                """, safeDone, safeTotal, percent, safe(message), taskId);
    }

    public void fileIndexed(String taskId, boolean reused, int chunks, int embeddingTotal,
                            int embeddingCompleted, int astSymbols) {
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET files_total = files_total + 1,
                    files_indexed = files_indexed + 1,
                    files_reused = files_reused + ?,
                    chunks_total = chunks_total + ?,
                    embeddings_total = embeddings_total + ?,
                    embeddings_completed = embeddings_completed + ?,
                    ast_files = ast_files + ?,
                    ast_symbols = ast_symbols + ?
                WHERE task_id = ?
                """, reused ? 1 : 0, Math.max(chunks, 0), Math.max(embeddingTotal, 0),
                Math.max(embeddingCompleted, 0), astSymbols > 0 ? 1 : 0,
                Math.max(astSymbols, 0), taskId);
    }

    public void error(String taskId, String stage, String filePath, String code,
                      String message, boolean retryable) {
        insertError(taskId, stage, filePath, code, message, retryable);
        int fileDelta = filePath == null || filePath.isBlank() ? 0 : 1;
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET files_total = files_total + ?, files_failed = files_failed + ?
                WHERE task_id = ?
                """, fileDelta, fileDelta, taskId);
    }

    public void warning(String taskId, String stage, String code, String message) {
        insertError(taskId, stage, "", code, message, false);
    }

    public void projectCounts(String taskId, JsonNode status) {
        int files = firstCount(status, "file_count", "files", "documents");
        int chunks = firstCount(status, "chunk_count", "chunks", "nodes");
        int embeddings = firstCount(status, "embedding_count", "embeddings", "embedded_chunks");
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET files_total = ?, files_indexed = ?, chunks_total = ?,
                    embeddings_total = ?, embeddings_completed = ?,
                    ast_files = ?, ast_symbols = ?
                WHERE task_id = ?
                """, files, files, chunks, embeddings, embeddings, files, chunks, taskId);
    }

    private void insertError(String taskId, String stage, String filePath, String code,
                             String message, boolean retryable) {
        jdbc.update("""
                INSERT INTO knowledge_build_errors
                    (task_id, stage, file_path, error_code, message, occurred_at, retryable)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskId, safe(stage), safe(filePath), safe(code), safe(message), now(), retryable);
    }

    public Map<String, Object> complete(String taskId, String repoId) {
        Map<String, Object> task = get(taskId);
        int filesTotal = number(task.get("filesTotal"));
        int filesIndexed = number(task.get("filesIndexed"));
        int embeddingsTotal = number(task.get("embeddingsTotal"));
        int embeddingsCompleted = number(task.get("embeddingsCompleted"));
        int astFiles = number(task.get("astFiles"));
        int filesFailed = number(task.get("filesFailed"));

        double fileCoverage = filesTotal == 0 ? 0 : Math.min(1.0, filesIndexed / (double) filesTotal);
        double embeddingCoverage = embeddingsTotal == 0 ? 1.0
                : Math.min(1.0, embeddingsCompleted / (double) embeddingsTotal);
        double astCoverage = filesIndexed == 0 ? 0 : Math.min(1.0, astFiles / (double) filesIndexed);
        double score = Math.round((fileCoverage * 0.55 + embeddingCoverage * 0.30 + astCoverage * 0.15) * 1000.0) / 10.0;
        String status = score >= 90 && filesFailed == 0 ? "excellent"
                : score >= 75 ? "good"
                : score >= 50 ? "degraded" : "poor";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fileCoverage", roundRatio(fileCoverage));
        report.put("embeddingCoverage", roundRatio(embeddingCoverage));
        report.put("astCoverage", roundRatio(astCoverage));
        report.put("failedFiles", filesFailed);
        report.put("reusedFiles", number(task.get("filesReused")));
        report.put("astSymbols", number(task.get("astSymbols")));
        String reportJson = JsonUtils.toJson(report);

        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET status = 'completed', finished_at = ?, progress = 100,
                    completed_steps = total_steps, message = '知识库构建完成',
                    quality_status = ?, quality_score = ?, quality_report = ?
                WHERE task_id = ?
                """, now(), status, score, reportJson, taskId);
        jdbc.update("""
                UPDATE repo_index
                SET quality_status = ?, quality_score = ?, quality_report = ?, last_task_id = ?
                WHERE repo_id = ?
                """, status, score, reportJson, taskId, repoId);
        return get(taskId);
    }

    public void fail(String taskId, String repoId, String message) {
        insertError(taskId, "build", "", "BUILD_FAILED", message, true);
        jdbc.update("""
                UPDATE knowledge_build_tasks
                SET status = 'failed', finished_at = ?, message = ?,
                    quality_status = 'failed', quality_score = 0
                WHERE task_id = ?
                """, now(), safe(message), taskId);
        jdbc.update("""
                UPDATE repo_index
                SET quality_status = 'failed', quality_score = 0, last_task_id = ?
                WHERE repo_id = ?
                """, taskId, repoId);
    }

    public Map<String, Object> get(String taskId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM knowledge_build_tasks WHERE task_id = ?",
                (rs, rowNum) -> taskRow(rs), taskId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("构建任务不存在: " + taskId);
        }
        return rows.getFirst();
    }

    public List<Map<String, Object>> list(String repoId, int limit) {
        return jdbc.query("""
                        SELECT * FROM knowledge_build_tasks
                        WHERE repo_id = ?
                        ORDER BY requested_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> taskRow(rs), repoId, Math.min(Math.max(limit, 1), 100));
    }

    public List<Map<String, Object>> errors(String taskId) {
        return jdbc.query("""
                        SELECT id, stage, file_path, error_code, message, occurred_at, retryable
                        FROM knowledge_build_errors
                        WHERE task_id = ?
                        ORDER BY id
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("stage", rs.getString("stage"));
                    row.put("filePath", rs.getString("file_path"));
                    row.put("errorCode", rs.getString("error_code"));
                    row.put("message", rs.getString("message"));
                    row.put("occurredAt", rs.getString("occurred_at"));
                    row.put("retryable", rs.getBoolean("retryable"));
                    return row;
                }, taskId);
    }

    public Map<String, Object> quality(String repoId) {
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT quality_status, quality_score, quality_report, last_task_id
                        FROM repo_index WHERE repo_id = ?
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", rs.getString("quality_status"));
                    row.put("score", rs.getDouble("quality_score"));
                    row.put("report", JsonUtils.parseObject(rs.getString("quality_report")));
                    row.put("lastTaskId", rs.getString("last_task_id"));
                    return row;
                }, repoId);
        return rows.isEmpty()
                ? Map.of("status", "unknown", "score", 0, "report", Map.of(), "lastTaskId", "")
                : rows.getFirst();
    }

    private Map<String, Object> taskRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", rs.getString("task_id"));
        row.put("repoId", rs.getString("repo_id"));
        row.put("status", rs.getString("status"));
        row.put("mode", rs.getString("mode"));
        row.put("requestedAt", rs.getString("requested_at"));
        row.put("startedAt", safe(rs.getString("started_at")));
        row.put("finishedAt", safe(rs.getString("finished_at")));
        row.put("baseCommitSha", safe(rs.getString("base_commit_sha")));
        row.put("targetCommitSha", safe(rs.getString("target_commit_sha")));
        row.put("totalSteps", rs.getInt("total_steps"));
        row.put("completedSteps", rs.getInt("completed_steps"));
        row.put("progress", rs.getDouble("progress"));
        row.put("message", safe(rs.getString("message")));
        row.put("filesTotal", rs.getInt("files_total"));
        row.put("filesIndexed", rs.getInt("files_indexed"));
        row.put("filesReused", rs.getInt("files_reused"));
        row.put("filesFailed", rs.getInt("files_failed"));
        row.put("chunksTotal", rs.getInt("chunks_total"));
        row.put("embeddingsTotal", rs.getInt("embeddings_total"));
        row.put("embeddingsCompleted", rs.getInt("embeddings_completed"));
        row.put("astFiles", rs.getInt("ast_files"));
        row.put("astSymbols", rs.getInt("ast_symbols"));
        row.put("qualityStatus", rs.getString("quality_status"));
        row.put("qualityScore", rs.getDouble("quality_score"));
        row.put("qualityReport", JsonUtils.parseObject(rs.getString("quality_report")));
        return row;
    }

    private static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).format(TS);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double roundRatio(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static int firstCount(JsonNode node, String... fields) {
        if (node == null) return 0;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) return value.asInt();
            if (value.isArray()) return value.size();
        }
        return 0;
    }
}
