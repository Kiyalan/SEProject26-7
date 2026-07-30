package com.repopilot.repository;

import com.repopilot.entity.IssueAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueAnalysisRepository extends JpaRepository<IssueAnalysis, String> {
    List<IssueAnalysis> findByRepoIdOrderByAnalyzedAtDesc(String repoId);
}
