package com.repopilot.entity;

import com.repopilot.entity.id.CommitFileId;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "commit_files")
public class CommitFile {

    @EmbeddedId
    private CommitFileId id;

    @Column(name = "repo_id", length = 64)
    private String repoId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "file_type", nullable = false, length = 16)
    private String fileType;

    @Column(name = "size")
    private Integer size = 0;

    @Column(name = "language", length = 64)
    private String language;

    @Lob
    @Column(name = "summary")
    private String summary;

    public CommitFileId getId() {
        return id;
    }

    public void setId(CommitFileId id) {
        this.id = id;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
