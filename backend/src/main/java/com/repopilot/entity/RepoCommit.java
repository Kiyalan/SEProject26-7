package com.repopilot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "repo_commits", uniqueConstraints = @UniqueConstraint(columnNames = {"repo_id", "commit_sha"}))
public class RepoCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_id", nullable = false, length = 64)
    private String repoId;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "parent_sha", length = 64)
    private String parentSha = "";

    @Lob
    @Column(name = "message")
    private String message;

    @Column(name = "author", length = 128)
    private String author = "";

    @Column(name = "committed_at", length = 32)
    private String committedAt = "";

    @Column(name = "indexed_at", length = 32)
    private String indexedAt = "";

    @Column(name = "status", length = 32)
    private String status = "ready";

    @Lob
    @Column(name = "summary")
    private String summary;

    @Lob
    @Column(name = "languages")
    private String languages = "{}";

    @Column(name = "readme_path", length = 512)
    private String readmePath = "";

    @Lob
    @Column(name = "readme_preview")
    private String readmePreview;

    @Column(name = "file_count")
    private Integer fileCount = 0;

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    @Lob
    @Column(name = "module_summary")
    private String moduleSummary;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getParentSha() {
        return parentSha;
    }

    public void setParentSha(String parentSha) {
        this.parentSha = parentSha;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(String committedAt) {
        this.committedAt = committedAt;
    }

    public String getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(String indexedAt) {
        this.indexedAt = indexedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getLanguages() {
        return languages;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }

    public String getReadmePath() {
        return readmePath;
    }

    public void setReadmePath(String readmePath) {
        this.readmePath = readmePath;
    }

    public String getReadmePreview() {
        return readmePreview;
    }

    public void setReadmePreview(String readmePreview) {
        this.readmePreview = readmePreview;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public void setFileCount(Integer fileCount) {
        this.fileCount = fileCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getModuleSummary() {
        return moduleSummary;
    }

    public void setModuleSummary(String moduleSummary) {
        this.moduleSummary = moduleSummary;
    }
}
