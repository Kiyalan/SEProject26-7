package com.repopilot.repository;

import com.repopilot.entity.CommitFile;
import com.repopilot.entity.id.CommitFileId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommitFileRepository extends JpaRepository<CommitFile, CommitFileId> {

    void deleteById_CommitSha(String commitSha);

    List<CommitFile> findById_CommitShaOrderById_PathAsc(String commitSha);

    List<CommitFile> findByRepoIdAndId_CommitShaAndFileType(String repoId, String commitSha, String fileType);

    @Query("SELECT COUNT(DISTINCT f.contentHash) FROM CommitFile f WHERE f.repoId = :repoId")
    long countDistinctContentHashByRepoId(@Param("repoId") String repoId);

    long countByRepoIdAndFileType(String repoId, String fileType);
}
