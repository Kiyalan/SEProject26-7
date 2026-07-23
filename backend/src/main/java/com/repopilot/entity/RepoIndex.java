package com.repopilot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "repo_index")
public class RepoIndex {

    @Id
    @Column(name = "repo_id", length = 64)
    private String repoId;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "default_branch", length = 128)
    private String defaultBranch;

    @Column(name = "indexed_at", length = 32)
    private String indexedAt;

    @Column(name = "file_count")
    private Integer fileCount = 0;

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    @Column(name = "status", length = 32)
    private String status = "idle";

    @Lob
    @Column(name = "summary")
    private String summary;

    @Lob
    @Column(name = "languages")
    private String languages = "{}";

    @Column(name = "readme_path", length = 512)
    private String readmePath = "";

    @Column(name = "commit_sha", length = 64)
    private String commitSha = "";

    @Lob
    @Column(name = "topics")
    private String topics = "[]";

    @Column(name = "license_name", length = 128)
    private String licenseName = "";

    @Lob
    @Column(name = "readme_preview")
    private String readmePreview;

    @Column(name = "active_commit_sha", length = 64)
    private String activeCommitSha = "";

    @Column(name = "quality_status", length = 32)
    private String qualityStatus = "unknown";

    @Column(name = "quality_score")
    private Double qualityScore = 0.0;

    @Lob
    @Column(name = "quality_report")
    private String qualityReport = "{}";

    @Column(name = "last_task_id", length = 64)
    private String lastTaskId = "";

    @Column(name = "codewiki_repo_id", length = 128)
    private String codeWikiRepoId = "";

    @Column(name = "graph_node_count")
    private Integer graphNodeCount = 0;

    @Column(name = "graph_edge_count")
    private Integer graphEdgeCount = 0;

    @Column(name = "graph_community_count")
    private Integer graphCommunityCount = 0;

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(String indexedAt) {
        this.indexedAt = indexedAt;
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

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getTopics() {
        return topics;
    }

    public void setTopics(String topics) {
        this.topics = topics;
    }

    public String getLicenseName() {
        return licenseName;
    }

    public void setLicenseName(String licenseName) {
        this.licenseName = licenseName;
    }

    public String getReadmePreview() {
        return readmePreview;
    }

    public void setReadmePreview(String readmePreview) {
        this.readmePreview = readmePreview;
    }

    public String getActiveCommitSha() {
        return activeCommitSha;
    }

    public void setActiveCommitSha(String activeCommitSha) {
        this.activeCommitSha = activeCommitSha;
    }

    public String getQualityStatus() {
        return qualityStatus;
    }

    public void setQualityStatus(String qualityStatus) {
        this.qualityStatus = qualityStatus;
    }

    public Double getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(Double qualityScore) {
        this.qualityScore = qualityScore;
    }

    public String getQualityReport() {
        return qualityReport;
    }

    public void setQualityReport(String qualityReport) {
        this.qualityReport = qualityReport;
    }

    public String getLastTaskId() {
        return lastTaskId;
    }

    public void setLastTaskId(String lastTaskId) {
        this.lastTaskId = lastTaskId;
    }

    public String getCodeWikiRepoId() {
        return codeWikiRepoId;
    }

    public void setCodeWikiRepoId(String codeWikiRepoId) {
        this.codeWikiRepoId = codeWikiRepoId;
    }

    public Integer getGraphNodeCount() {
        return graphNodeCount;
    }

    public void setGraphNodeCount(Integer graphNodeCount) {
        this.graphNodeCount = graphNodeCount;
    }

    public Integer getGraphEdgeCount() {
        return graphEdgeCount;
    }

    public void setGraphEdgeCount(Integer graphEdgeCount) {
        this.graphEdgeCount = graphEdgeCount;
    }

    public Integer getGraphCommunityCount() {
        return graphCommunityCount;
    }

    public void setGraphCommunityCount(Integer graphCommunityCount) {
        this.graphCommunityCount = graphCommunityCount;
    }
}
