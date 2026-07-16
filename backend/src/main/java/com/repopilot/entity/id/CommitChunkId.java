package com.repopilot.entity.id;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class CommitChunkId implements Serializable {

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "file_path", length = 512)
    private String filePath;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    public CommitChunkId() {
    }

    public CommitChunkId(String commitSha, String filePath, Integer chunkIndex) {
        this.commitSha = commitSha;
        this.filePath = filePath;
        this.chunkIndex = chunkIndex;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitChunkId that)) {
            return false;
        }
        return Objects.equals(commitSha, that.commitSha)
                && Objects.equals(filePath, that.filePath)
                && Objects.equals(chunkIndex, that.chunkIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitSha, filePath, chunkIndex);
    }
}
