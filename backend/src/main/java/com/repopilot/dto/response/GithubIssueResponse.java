package com.repopilot.dto.response;

import java.util.List;

public record GithubIssueResponse(
        String id,
        String repoId,
        int number,
        String title,
        String body,
        String state,
        String author,
        String createdAt,
        String updatedAt,
        List<String> labels,
        String htmlUrl,
        int comments,
        String milestone,
        String project
) {
}
