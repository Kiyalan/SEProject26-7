package com.repopilot.repository;

import com.repopilot.entity.FileContent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileContentRepository extends JpaRepository<FileContent, String> {
}
