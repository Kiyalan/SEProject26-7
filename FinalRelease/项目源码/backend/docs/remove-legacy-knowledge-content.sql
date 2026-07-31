-- Optional manual cleanup after CodeWiki migration.
-- Back up the database and stop the backend before running this script.
-- These tables are intentionally not dropped automatically so existing H2 data is preserved.
DROP TABLE IF EXISTS commit_chunks;
DROP TABLE IF EXISTS commit_files;
DROP TABLE IF EXISTS file_contents;
DROP TABLE IF EXISTS repo_commits;
