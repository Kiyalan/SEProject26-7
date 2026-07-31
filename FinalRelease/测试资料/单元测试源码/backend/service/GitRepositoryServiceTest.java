package com.repopilot.service;

import com.repopilot.config.AppProperties;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitRepositoryServiceTest {
    @TempDir
    Path temp;

    @Test
    void readsCommitHistoryFromLocalFixture() throws Exception {
        String ownerLogin = "fixture-owner";
        Path repositoryPath = temp.resolve(ownerLogin).resolve("repo-1");
        Files.createDirectories(repositoryPath);
        try (Git git = Git.init().setDirectory(repositoryPath.toFile()).call()) {
            Files.writeString(repositoryPath.resolve("README.md"), "hello");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("initial documentation")
                    .setAuthor("Fixture", "fixture@example.test").call();
        }
        AppProperties properties = new AppProperties(null, null, null,
                new AppProperties.CodeWiki("http://localhost", temp.toString(), "/repos",
                        false, 1, 1, 1, 1));
        GitRepositoryService service = new GitRepositoryService(properties);

        var history = service.history("repo-1", ownerLogin, 10);

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().get("content").toString())
                .contains("initial documentation", "Fixture");
    }
}
