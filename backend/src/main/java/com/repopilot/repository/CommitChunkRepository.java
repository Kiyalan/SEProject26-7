package com.repopilot.repository;

import com.repopilot.entity.CommitChunk;
import com.repopilot.entity.id.CommitChunkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommitChunkRepository extends JpaRepository<CommitChunk, CommitChunkId> {

    void deleteById_CommitSha(String commitSha);

    List<CommitChunk> findByRepoIdAndId_CommitSha(String repoId, String commitSha);

    long countByRepoId(String repoId);
}
