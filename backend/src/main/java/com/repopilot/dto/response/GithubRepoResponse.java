package com.repopilot.dto.response;

public record GithubRepoResponse(
        String id,
        String name,
        String fullName,
        String description,
        int stars,
        int openIssues,
        String language,
        String lastSync,
        String syncStatus,
        String htmlUrl,
        boolean isPrivate,
        String defaultBranch
) {
}
