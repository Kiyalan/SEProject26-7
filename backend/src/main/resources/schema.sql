CREATE TABLE IF NOT EXISTS repo_index (
    repo_id VARCHAR(64) PRIMARY KEY,
    owner_login VARCHAR(128) NOT NULL DEFAULT '',
    full_name VARCHAR(255) NOT NULL,
    default_branch VARCHAR(128),
    indexed_at VARCHAR(32),
    file_count INT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'idle',
    summary CLOB,
    languages CLOB DEFAULT '{}',
    readme_path VARCHAR(512) DEFAULT '',
    commit_sha VARCHAR(64) DEFAULT '',
    topics CLOB DEFAULT '[]',
    license_name VARCHAR(128) DEFAULT '',
    readme_preview CLOB,
    active_commit_sha VARCHAR(64) DEFAULT '',
    quality_status VARCHAR(32) DEFAULT 'unknown',
    quality_score DOUBLE DEFAULT 0,
    quality_report CLOB DEFAULT '{}',
    last_task_id VARCHAR(64) DEFAULT '',
    codewiki_repo_id VARCHAR(128) DEFAULT '',
    graph_node_count INT DEFAULT 0,
    graph_edge_count INT DEFAULT 0,
    graph_community_count INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_repo_index_owner
    ON repo_index(owner_login);

CREATE TABLE IF NOT EXISTS repo_index_settings (
    repo_id VARCHAR(64) PRIMARY KEY,
    owner_login VARCHAR(128) NOT NULL DEFAULT '',
    index_each_commit BOOLEAN DEFAULT FALSE,
    max_commits INT DEFAULT 30,
    active_commit_sha VARCHAR(64) DEFAULT ''
);

CREATE TABLE IF NOT EXISTS knowledge_build_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
    owner_login VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    mode VARCHAR(32) DEFAULT 'incremental',
    requested_at VARCHAR(32) NOT NULL,
    started_at VARCHAR(32),
    finished_at VARCHAR(32),
    base_commit_sha VARCHAR(64) DEFAULT '',
    target_commit_sha VARCHAR(64) DEFAULT '',
    total_steps INT DEFAULT 1,
    completed_steps INT DEFAULT 0,
    progress DOUBLE DEFAULT 0,
    message CLOB DEFAULT '',
    files_total INT DEFAULT 0,
    files_indexed INT DEFAULT 0,
    files_reused INT DEFAULT 0,
    files_failed INT DEFAULT 0,
    chunks_total INT DEFAULT 0,
    embeddings_total INT DEFAULT 0,
    embeddings_completed INT DEFAULT 0,
    ast_files INT DEFAULT 0,
    ast_symbols INT DEFAULT 0,
    quality_status VARCHAR(32) DEFAULT 'unknown',
    quality_score DOUBLE DEFAULT 0,
    quality_report CLOB DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_knowledge_tasks_repo_requested
    ON knowledge_build_tasks(repo_id, requested_at);

CREATE INDEX IF NOT EXISTS idx_knowledge_build_tasks_owner
    ON knowledge_build_tasks(owner_login);

CREATE TABLE IF NOT EXISTS knowledge_build_errors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    file_path VARCHAR(1024) DEFAULT '',
    error_code VARCHAR(64) DEFAULT '',
    message CLOB NOT NULL,
    occurred_at VARCHAR(32) NOT NULL,
    retryable BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_knowledge_errors_task
    ON knowledge_build_errors(task_id);

CREATE TABLE IF NOT EXISTS issue_analysis (
    issue_id VARCHAR(64) PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
    owner_login VARCHAR(128) NOT NULL DEFAULT '',
    issue_number INT,
    issue_title CLOB NOT NULL,
    issue_type VARCHAR(64) NOT NULL,
    confidence DOUBLE DEFAULT 0,
    summary CLOB NOT NULL,
    suggested_reply CLOB NOT NULL,
    reason CLOB,
    related_files CLOB DEFAULT '[]',
    analyzed_at VARCHAR(32) NOT NULL,
    llm_enhanced BOOLEAN DEFAULT FALSE,
    issue_labels CLOB DEFAULT '[]',
    issue_milestone VARCHAR(255) DEFAULT '',
    issue_project VARCHAR(255) DEFAULT ''
);

CREATE TABLE IF NOT EXISTS repo_faq_items (
    id VARCHAR(64) PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
    owner_login VARCHAR(128) NOT NULL DEFAULT '',
    category VARCHAR(64) NOT NULL,
    question CLOB NOT NULL,
    answer CLOB NOT NULL,
    related_files CLOB DEFAULT '[]',
    confidence DOUBLE DEFAULT 0,
    updated_at VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_repo_faq_repo
    ON repo_faq_items(repo_id, category);

CREATE TABLE IF NOT EXISTS user_notification_settings (
    user_login VARCHAR(128) PRIMARY KEY,
    email VARCHAR(255) DEFAULT '',
    enabled BOOLEAN DEFAULT FALSE,
    notify_on_knowledge_build BOOLEAN DEFAULT TRUE,
    notify_on_issue_analysis BOOLEAN DEFAULT FALSE,
    notify_on_wiki_ready BOOLEAN DEFAULT TRUE,
    delivery_mode VARCHAR(32) DEFAULT 'stub',
    updated_at VARCHAR(32) NOT NULL,
    last_test_at VARCHAR(32) DEFAULT '',
    last_test_message CLOB DEFAULT ''
);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    admin_name VARCHAR(128) NOT NULL,
    action VARCHAR(255) NOT NULL,
    target VARCHAR(512) DEFAULT '',
    result VARCHAR(32) NOT NULL,
    created_at VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created
    ON admin_audit_logs(created_at);

CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    email VARCHAR(256) DEFAULT '',
    role VARCHAR(32) NOT NULL DEFAULT 'user',
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at VARCHAR(32) DEFAULT '',
    last_login_at VARCHAR(32) DEFAULT ''
);
