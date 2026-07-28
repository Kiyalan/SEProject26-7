package com.repopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBuildTaskServiceTest {
    @Test
    void projectsCodeWikiCountsAndCompletesTask() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tasks;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE knowledge_build_tasks (
                  task_id VARCHAR(64) PRIMARY KEY, repo_id VARCHAR(64), status VARCHAR(32),
                  mode VARCHAR(32), requested_at VARCHAR(32), started_at VARCHAR(32),
                  finished_at VARCHAR(32), base_commit_sha VARCHAR(64) DEFAULT '',
                  target_commit_sha VARCHAR(64) DEFAULT '', total_steps INT DEFAULT 1,
                  completed_steps INT DEFAULT 0, progress DOUBLE DEFAULT 0, message CLOB DEFAULT '',
                  files_total INT DEFAULT 0, files_indexed INT DEFAULT 0, files_reused INT DEFAULT 0,
                  files_failed INT DEFAULT 0, chunks_total INT DEFAULT 0, embeddings_total INT DEFAULT 0,
                  embeddings_completed INT DEFAULT 0, ast_files INT DEFAULT 0, ast_symbols INT DEFAULT 0,
                  quality_status VARCHAR(32) DEFAULT 'unknown', quality_score DOUBLE DEFAULT 0,
                  quality_report CLOB DEFAULT '{}')
                """);
        jdbc.execute("""
                CREATE TABLE knowledge_build_errors (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64), stage VARCHAR(64),
                  file_path VARCHAR(1024), error_code VARCHAR(64), message CLOB,
                  occurred_at VARCHAR(32), retryable BOOLEAN)
                """);
        jdbc.execute("""
                CREATE TABLE repo_index (
                  repo_id VARCHAR(64) PRIMARY KEY, quality_status VARCHAR(32),
                  quality_score DOUBLE, quality_report CLOB, last_task_id VARCHAR(64))
                """);
        jdbc.update("INSERT INTO repo_index(repo_id) VALUES ('repo')");
        KnowledgeBuildTaskService service = new KnowledgeBuildTaskService(jdbc);
        String taskId = service.create("repo", "full");
        service.start(taskId);
        service.projectCounts(taskId, new ObjectMapper().readTree(
                "{\"file_count\":12,\"chunk_count\":34,\"embedding_count\":34}"));

        service.complete(taskId, "repo");

        assertThat(service.get(taskId))
                .containsEntry("status", "completed")
                .containsEntry("filesIndexed", 12)
                .containsEntry("chunksTotal", 34)
                .containsEntry("progress", 100.0);
    }
}
