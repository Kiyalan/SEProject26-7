package com.repopilot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "issue_analysis")
public class IssueAnalysis {

    @Id
    @Column(name = "issue_id", length = 64)
    private String issueId;

    @Column(name = "repo_id", nullable = false, length = 64)
    private String repoId;

    @Column(name = "issue_number")
    private Integer issueNumber;

    @Lob
    @Column(name = "issue_title", nullable = false)
    private String issueTitle;

    @Column(name = "issue_type", nullable = false, length = 64)
    private String issueType;

    @Column(name = "confidence")
    private Double confidence = 0.0;

    @Lob
    @Column(name = "summary", nullable = false)
    private String summary;

    @Lob
    @Column(name = "suggested_reply", nullable = false)
    private String suggestedReply;

    @Lob
    @Column(name = "reason")
    private String reason;

    @Lob
    @Column(name = "related_files")
    private String relatedFiles = "[]";

    @Column(name = "analyzed_at", nullable = false, length = 32)
    private String analyzedAt;

    @Column(name = "llm_enhanced")
    private Boolean llmEnhanced = false;

    @Lob
    @Column(name = "issue_labels")
    private String issueLabels = "[]";

    @Column(name = "issue_milestone", length = 255)
    private String issueMilestone = "";

    @Column(name = "issue_project", length = 255)
    private String issueProject = "";

    public String getIssueId() {
        return issueId;
    }

    public void setIssueId(String issueId) {
        this.issueId = issueId;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public Integer getIssueNumber() {
        return issueNumber;
    }

    public void setIssueNumber(Integer issueNumber) {
        this.issueNumber = issueNumber;
    }

    public String getIssueTitle() {
        return issueTitle;
    }

    public void setIssueTitle(String issueTitle) {
        this.issueTitle = issueTitle;
    }

    public String getIssueType() {
        return issueType;
    }

    public void setIssueType(String issueType) {
        this.issueType = issueType;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSuggestedReply() {
        return suggestedReply;
    }

    public void setSuggestedReply(String suggestedReply) {
        this.suggestedReply = suggestedReply;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRelatedFiles() {
        return relatedFiles;
    }

    public void setRelatedFiles(String relatedFiles) {
        this.relatedFiles = relatedFiles;
    }

    public String getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(String analyzedAt) {
        this.analyzedAt = analyzedAt;
    }

    public Boolean getLlmEnhanced() {
        return llmEnhanced;
    }

    public void setLlmEnhanced(Boolean llmEnhanced) {
        this.llmEnhanced = llmEnhanced;
    }

    public String getIssueLabels() {
        return issueLabels;
    }

    public void setIssueLabels(String issueLabels) {
        this.issueLabels = issueLabels;
    }

    public String getIssueMilestone() {
        return issueMilestone;
    }

    public void setIssueMilestone(String issueMilestone) {
        this.issueMilestone = issueMilestone;
    }

    public String getIssueProject() {
        return issueProject;
    }

    public void setIssueProject(String issueProject) {
        this.issueProject = issueProject;
    }
}
