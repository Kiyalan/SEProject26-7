package com.repopilot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void formatRepo_extractsAllFields() throws Exception {
        GitHubClient client = new GitHubClient();
        String json = """
            {
                "id": 123,
                "name": "test-repo",
                "full_name": "owner/test-repo",
                "description": "A test repository",
                "stargazers_count": 100,
                "open_issues_count": 5,
                "language": "Java",
                "html_url": "https://github.com/owner/test-repo",
                "private": false,
                "default_branch": "main"
            }
            """;
        JsonNode repo = MAPPER.readTree(json);

        Map<String, Object> result = client.formatRepo(repo);

        assertThat(result.get("id")).isEqualTo("123");
        assertThat(result.get("name")).isEqualTo("test-repo");
        assertThat(result.get("fullName")).isEqualTo("owner/test-repo");
        assertThat(result.get("description")).isEqualTo("A test repository");
        assertThat(result.get("stars")).isEqualTo(100);
        assertThat(result.get("openIssues")).isEqualTo(5);
        assertThat(result.get("language")).isEqualTo("Java");
        assertThat(result.get("htmlUrl")).isEqualTo("https://github.com/owner/test-repo");
        assertThat(result.get("private")).isEqualTo(false);
        assertThat(result.get("defaultBranch")).isEqualTo("main");
        assertThat(result.get("syncStatus")).isEqualTo("synced");
    }

    @Test
    void formatRepo_handlesNullDescription() throws Exception {
        GitHubClient client = new GitHubClient();
        String json = """
            {
                "id": 123,
                "name": "repo",
                "full_name": "owner/repo",
                "description": null,
                "stargazers_count": 0,
                "open_issues_count": 0,
                "language": null,
                "html_url": "",
                "private": false,
                "default_branch": "master"
            }
            """;
        JsonNode repo = MAPPER.readTree(json);

        Map<String, Object> result = client.formatRepo(repo);

        assertThat(result.get("description")).isEqualTo("");
        assertThat(result.get("language")).isEqualTo("—");
    }

    @Test
    void formatIssue_extractsAllFields() throws Exception {
        GitHubClient client = new GitHubClient();
        String json = """
            {
                "id": 456,
                "number": 42,
                "title": "Bug fix",
                "body": "Fixed a bug",
                "state": "open",
                "user": {"login": "contributor"},
                "created_at": "2024-01-15T10:30:00Z",
                "updated_at": "2024-01-16T15:45:00Z",
                "labels": [{"name": "bug"}, {"name": "priority"}],
                "html_url": "https://github.com/owner/repo/issues/42",
                "comments": 3,
                "milestone": {"title": "v1.0"}
            }
            """;
        JsonNode issue = MAPPER.readTree(json);

        Map<String, Object> result = client.formatIssue(issue, "owner/repo");

        assertThat(result.get("id")).isEqualTo("456");
        assertThat(result.get("repoId")).isEqualTo("owner/repo");
        assertThat(result.get("number")).isEqualTo(42);
        assertThat(result.get("title")).isEqualTo("Bug fix");
        assertThat(result.get("body")).isEqualTo("Fixed a bug");
        assertThat(result.get("state")).isEqualTo("open");
        assertThat(result.get("author")).isEqualTo("contributor");
        assertThat(result.get("createdAt")).isEqualTo("2024-01-15");
        assertThat(result.get("updatedAt")).isEqualTo("2024-01-16");
        assertThat(result.get("labels")).asList().containsExactly("bug", "priority");
        assertThat(result.get("comments")).isEqualTo(3);
        assertThat(result.get("milestone")).isEqualTo("v1.0");
        assertThat(result.get("project")).isEqualTo("v1.0");
    }

    @Test
    void formatIssue_handlesNullBody() throws Exception {
        GitHubClient client = new GitHubClient();
        String json = """
            {
                "id": 1,
                "number": 1,
                "title": "Issue",
                "body": null,
                "state": "open",
                "user": {"login": "user"},
                "created_at": "2024-01-01T00:00:00Z",
                "updated_at": "2024-01-01T00:00:00Z",
                "labels": [],
                "html_url": "",
                "comments": 0
            }
            """;
        JsonNode issue = MAPPER.readTree(json);

        Map<String, Object> result = client.formatIssue(issue, "repo");

        assertThat(result.get("body")).isEqualTo("");
    }

    @Test
    void formatIssue_handlesMissingMilestone() throws Exception {
        GitHubClient client = new GitHubClient();
        String json = """
            {
                "id": 1,
                "number": 1,
                "title": "Issue",
                "body": "desc",
                "state": "closed",
                "user": {"login": "user"},
                "created_at": "2024-01-01T00:00:00Z",
                "updated_at": "2024-01-01T00:00:00Z",
                "labels": [],
                "html_url": "",
                "comments": 0
            }
            """;
        JsonNode issue = MAPPER.readTree(json);

        Map<String, Object> result = client.formatIssue(issue, "repo");

        assertThat(result.get("milestone")).isEqualTo("");
        assertThat(result.get("project")).isEqualTo("");
    }

    @Test
    void encodePath_encodesCorrectly() {
        String result = GitHubClient.encodePath("path/to/file with spaces");

        assertThat(result).doesNotContain(" ");
    }
}
