package com.repopilot.service;

import com.repopilot.config.AppProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GitRepositoryService {
    private final AppProperties.CodeWiki properties;

    public GitRepositoryService(AppProperties properties) {
        this.properties = properties.codewiki();
    }

    public synchronized SyncResult sync(String repoId, String fullName, String defaultBranch, String token) {
        Path path = hostPath(repoId);
        UsernamePasswordCredentialsProvider credentials =
                new UsernamePasswordCredentialsProvider("x-access-token", token);
        try {
            Files.createDirectories(path.getParent());
            boolean cloned = !Files.isDirectory(path.resolve(".git"));
            if (cloned) {
                try (Git ignored = Git.cloneRepository()
                        .setURI("https://github.com/" + fullName + ".git")
                        .setDirectory(path.toFile())
                        .setCredentialsProvider(credentials)
                        .setCloneAllBranches(false)
                        .setBranch(defaultBranch)
                        .call()) {
                    // Token is supplied only through JGit's credentials provider.
                }
            }
            try (Git git = Git.open(path.toFile())) {
                Repository repository = git.getRepository();
                String oldHead = repository.resolve("HEAD") == null ? "" : repository.resolve("HEAD").name();
                git.fetch().setRemote("origin").setCredentialsProvider(credentials).call();
                ObjectId remoteHead = repository.resolve("refs/remotes/origin/" + defaultBranch);
                if (remoteHead == null) throw new IllegalStateException("远端默认分支不存在: " + defaultBranch);
                git.checkout().setName(defaultBranch).setCreateBranch(
                        repository.findRef(defaultBranch) == null).setStartPoint("origin/" + defaultBranch).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteHead.name()).call();
                return new SyncResult(path, containerPath(repoId), cloned, oldHead, remoteHead.name());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("同步 Git 仓库失败: " + rootMessage(ex), ex);
        } finally {
            credentials.clear();
        }
    }

    public List<Map<String, Object>> history(String repoId, int limit) {
        try (Git git = Git.open(hostPath(repoId).toFile())) {
            List<Map<String, Object>> result = new ArrayList<>();
            int index = 0;
            for (RevCommit commit : git.log().setMaxCount(Math.min(Math.max(limit, 1), 50)).call()) {
                List<DiffEntry> changes = commit.getParentCount() == 0
                        ? List.of() : scan(git.getRepository(), commit.getParent(0), commit);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", "git/commits/" + commit.abbreviate(12).name());
                row.put("line", ++index);
                row.put("endLine", index);
                row.put("symbolName", commit.name());
                row.put("symbolKind", "commit");
                row.put("score", limit - index + 1);
                row.put("retrievalType", "structured");
                row.put("sourceType", "commit_history");
                row.put("content", "sha: " + commit.name()
                        + "\nauthor: " + commit.getAuthorIdent().getName()
                        + "\ntime: " + commit.getAuthorIdent().getWhenAsInstant()
                                .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        + "\nmessage: " + commit.getFullMessage().replace('\n', ' ')
                        + "\nchangedPaths: " + changes.stream().limit(20)
                                .map(change -> change.getChangeType() + ":" + change.getNewPath()).toList());
                result.add(row);
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Git 历史失败: " + rootMessage(ex), ex);
        }
    }

    public Map<String, Object> compare(String repoId, String baseSha, String headSha) {
        try (Git git = Git.open(hostPath(repoId).toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit base = walk.parseCommit(git.getRepository().resolve(baseSha));
            RevCommit head = walk.parseCommit(git.getRepository().resolve(headSha));
            List<DiffEntry> entries = scan(git.getRepository(), base, head);
            return Map.of(
                    "baseSha", base.name(), "headSha", head.name(),
                    "baseMessage", base.getShortMessage(), "headMessage", head.getShortMessage(),
                    "added", paths(entries, DiffEntry.ChangeType.ADD),
                    "removed", paths(entries, DiffEntry.ChangeType.DELETE),
                    "modified", entries.stream().filter(e -> e.getChangeType() != DiffEntry.ChangeType.ADD
                                    && e.getChangeType() != DiffEntry.ChangeType.DELETE)
                            .map(DiffEntry::getNewPath).toList(),
                    "unchanged", 0, "sharedBlobCount", 0, "previews", List.of());
        } catch (Exception ex) {
            throw new IllegalArgumentException("比较 Git 提交失败: " + rootMessage(ex), ex);
        }
    }

    public Path hostPath(String repoId) {
        return Path.of(properties.hostRepoRoot()).toAbsolutePath().normalize().resolve(safeRepoId(repoId));
    }

    private String containerPath(String repoId) {
        return Path.of(properties.containerRepoRoot()).resolve(safeRepoId(repoId))
                .toString().replace('\\', '/');
    }

    private static List<DiffEntry> scan(Repository repository, RevCommit base, RevCommit head) throws Exception {
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            return formatter.scan(base.getTree(), head.getTree());
        }
    }

    private static List<String> paths(List<DiffEntry> entries, DiffEntry.ChangeType type) {
        return entries.stream().filter(e -> e.getChangeType() == type)
                .map(e -> type == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath()).toList();
    }

    private static String safeRepoId(String repoId) {
        if (repoId == null || !repoId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("非法 repoId");
        }
        return repoId;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record SyncResult(Path hostPath, String codeWikiPath, boolean cloned, String oldHead, String head) {}
}
