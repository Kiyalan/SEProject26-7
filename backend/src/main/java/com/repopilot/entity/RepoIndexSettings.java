package com.repopilot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "repo_index_settings")
public class RepoIndexSettings {

    @Id
    @Column(name = "repo_id", length = 64)
    private String repoId;

    @Column(name = "index_each_commit")
    private Boolean indexEachCommit = false;

    @Column(name = "max_commits")
    private Integer maxCommits = 30;

    @Column(name = "active_commit_sha", length = 64)
    private String activeCommitSha = "";

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public Boolean getIndexEachCommit() {
        return indexEachCommit;
    }

    public void setIndexEachCommit(Boolean indexEachCommit) {
        this.indexEachCommit = indexEachCommit;
    }

    public Integer getMaxCommits() {
        return maxCommits;
    }

    public void setMaxCommits(Integer maxCommits) {
        this.maxCommits = maxCommits;
    }

    public String getActiveCommitSha() {
        return activeCommitSha;
    }

    public void setActiveCommitSha(String activeCommitSha) {
        this.activeCommitSha = activeCommitSha;
    }
}
