package com.repopilot.repository;

import com.repopilot.entity.IssueAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueAnalysisRepository extends JpaRepository<IssueAnalysis, String> {
}
