package com.repopilot.repository;

import com.repopilot.entity.RepoCommit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoCommitRepository extends JpaRepository<RepoCommit, Long> {

    Optional<RepoCommit> findByRepoIdAndCommitSha(String repoId, String commitSha);

    List<RepoCommit> findByRepoIdOrderByCommittedAtDesc(String repoId);

    long countByRepoId(String repoId);

    Optional<RepoCommit> findFirstByRepoIdAndFileCountGreaterThanOrderByCommittedAtDesc(String repoId, int fileCount);
}
