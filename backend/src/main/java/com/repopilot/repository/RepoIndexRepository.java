package com.repopilot.repository;

import com.repopilot.entity.RepoIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepoIndexRepository extends JpaRepository<RepoIndex, String> {

    @Modifying
    @Query("UPDATE RepoIndex r SET r.status = 'idle' WHERE r.repoId = :repoId AND r.status = 'indexing'")
    int resetIndexingStatus(@Param("repoId") String repoId);
}
