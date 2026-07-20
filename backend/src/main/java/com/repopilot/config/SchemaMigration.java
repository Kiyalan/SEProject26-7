package com.repopilot.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigration {

    public SchemaMigration(JdbcTemplate jdbc) {
        migrate(jdbc, "issue_labels", "ALTER TABLE issue_analysis ADD COLUMN issue_labels CLOB DEFAULT '[]'");
        migrate(jdbc, "issue_milestone", "ALTER TABLE issue_analysis ADD COLUMN issue_milestone VARCHAR(255) DEFAULT ''");
        migrate(jdbc, "issue_project", "ALTER TABLE issue_analysis ADD COLUMN issue_project VARCHAR(255) DEFAULT ''");
    }

    private void migrate(JdbcTemplate jdbc, String column, String ddl) {
        try {
            boolean exists = Boolean.TRUE.equals(jdbc.query(
                    "SELECT COUNT(*) > 0 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'ISSUE_ANALYSIS' AND COLUMN_NAME = ?",
                    rs -> rs.next() && rs.getBoolean(1),
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
