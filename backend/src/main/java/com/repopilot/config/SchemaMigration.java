package com.repopilot.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigration {

    public SchemaMigration(JdbcTemplate jdbc) {
        addColumnIfMissing(jdbc, "issue_analysis", "issue_labels",
                "ALTER TABLE issue_analysis ADD COLUMN issue_labels CLOB DEFAULT '[]'");
        addColumnIfMissing(jdbc, "issue_analysis", "issue_milestone",
                "ALTER TABLE issue_analysis ADD COLUMN issue_milestone VARCHAR(255) DEFAULT ''");
        addColumnIfMissing(jdbc, "issue_analysis", "issue_project",
                "ALTER TABLE issue_analysis ADD COLUMN issue_project VARCHAR(255) DEFAULT ''");
        addColumnIfMissing(jdbc, "repo_index", "quality_status",
                "ALTER TABLE repo_index ADD COLUMN quality_status VARCHAR(32) DEFAULT 'unknown'");
        addColumnIfMissing(jdbc, "repo_index", "quality_score",
                "ALTER TABLE repo_index ADD COLUMN quality_score DOUBLE DEFAULT 0");
        addColumnIfMissing(jdbc, "repo_index", "quality_report",
                "ALTER TABLE repo_index ADD COLUMN quality_report CLOB DEFAULT '{}'");
        addColumnIfMissing(jdbc, "repo_index", "last_task_id",
                "ALTER TABLE repo_index ADD COLUMN last_task_id VARCHAR(64) DEFAULT ''");
        addColumnIfMissing(jdbc, "repo_index", "codewiki_repo_id",
                "ALTER TABLE repo_index ADD COLUMN codewiki_repo_id VARCHAR(128) DEFAULT ''");
        addColumnIfMissing(jdbc, "repo_index", "graph_node_count",
                "ALTER TABLE repo_index ADD COLUMN graph_node_count INT DEFAULT 0");
        addColumnIfMissing(jdbc, "repo_index", "graph_edge_count",
                "ALTER TABLE repo_index ADD COLUMN graph_edge_count INT DEFAULT 0");
        addColumnIfMissing(jdbc, "repo_index", "graph_community_count",
                "ALTER TABLE repo_index ADD COLUMN graph_community_count INT DEFAULT 0");
    }

    private void addColumnIfMissing(JdbcTemplate jdbc, String table, String column, String ddl) {
        try {
            boolean exists = Boolean.TRUE.equals(jdbc.query(
                    "SELECT COUNT(*) > 0 FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                    rs -> rs.next() && rs.getBoolean(1),
                    table.toUpperCase(),
                    column.toUpperCase()
            ));
            if (!exists) {
                jdbc.execute(ddl);
            }
        } catch (Exception ignored) {
            try {
                jdbc.execute(ddl);
            } catch (Exception ignoredAgain) {
            }
        }
    }
}
