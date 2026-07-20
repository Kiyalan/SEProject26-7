CREATE TABLE IF NOT EXISTS repo_index (
    repo_id VARCHAR(64) PRIMARY KEY,
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
    active_commit_sha VARCHAR(64) DEFAULT ''
);

CREATE TABLE IF NOT EXISTS repo_index_settings (
    repo_id VARCHAR(64) PRIMARY KEY,
    index_each_commit BOOLEAN DEFAULT FALSE,
    max_commits INT DEFAULT 30,
    active_commit_sha VARCHAR(64) DEFAULT ''
);

CREATE TABLE IF NOT EXISTS repo_commits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    parent_sha VARCHAR(64) DEFAULT '',
    message CLOB,
    author VARCHAR(128) DEFAULT '',
    committed_at VARCHAR(32) DEFAULT '',
    indexed_at VARCHAR(32) DEFAULT '',
    status VARCHAR(32) DEFAULT 'ready',
    summary CLOB,
    languages CLOB DEFAULT '{}',
    readme_path VARCHAR(512) DEFAULT '',
    readme_preview CLOB,
    file_count INT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    UNIQUE (repo_id, commit_sha)
);

CREATE TABLE IF NOT EXISTS commit_files (
    repo_id VARCHAR(64) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    size INT DEFAULT 0,
    language VARCHAR(64),
    PRIMARY KEY (commit_sha, path)
);

CREATE TABLE IF NOT EXISTS file_contents (
    content_hash VARCHAR(64) PRIMARY KEY,
    content CLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS commit_chunks (
    repo_id VARCHAR(64) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    chunk_index INT NOT NULL,
    content CLOB NOT NULL,
    start_line INT DEFAULT 1,
    PRIMARY KEY (commit_sha, file_path, chunk_index)
);

CREATE TABLE IF NOT EXISTS issue_analysis (
    issue_id VARCHAR(64) PRIMARY KEY,
    repo_id VARCHAR(64) NOT NULL,
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
