package com.repopilot.entity;

import com.repopilot.entity.id.CommitChunkId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "commit_chunks")
public class CommitChunk {

    @EmbeddedId
    private CommitChunkId id;

    @Column(name = "repo_id", length = 64)
    private String repoId;

    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "start_line")
    private Integer startLine = 1;

    public CommitChunkId getId() {
        return id;
    }

    public void setId(CommitChunkId id) {
        this.id = id;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }
}
