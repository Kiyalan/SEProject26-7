package com.repopilot.entity.id;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class CommitFileId implements Serializable {

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "path", length = 1024)
    private String path;

    public CommitFileId() {
    }

    public CommitFileId(String commitSha, String path) {
        this.commitSha = commitSha;
        this.path = path;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitFileId that)) {
            return false;
        }
        return Objects.equals(commitSha, that.commitSha) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitSha, path);
    }
}
