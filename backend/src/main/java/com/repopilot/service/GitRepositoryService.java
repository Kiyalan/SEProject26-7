package com.repopilot.service;

import com.repopilot.config.AppProperties;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GitRepositoryService {
    private final AppProperties.CodeWiki properties;
    private final ConcurrentHashMap<String, Object> syncLocks = new ConcurrentHashMap<>();

    public GitRepositoryService(AppProperties properties) {
        this.properties = properties.codewiki();
    }

    public SyncResult sync(String repoId, String fullName, String ownerLogin, String token, String branch) {
        return sync(repoId, fullName, ownerLogin, token, branch, null);
    }

    public SyncResult sync(String repoId, String fullName, String ownerLogin, String token, String branch,
                           java.util.function.Consumer<String> onProgress) {
        Object lock = syncLocks.computeIfAbsent(repoId + "/" + ownerLogin, k -> new Object());
        synchronized (lock) {
            Path path = hostPath(repoId, ownerLogin);
            UsernamePasswordCredentialsProvider credentials =
                    new UsernamePasswordCredentialsProvider("x-access-token", token);
            try {
                Files.createDirectories(path.getParent());
                boolean cloned = !Files.isDirectory(path.resolve(".git"));
                if (cloned) {
                    if (onProgress != null) onProgress.accept("正在克隆 Git 仓库 (首次构建，耗时取决于仓库大小)");
                    try (Git ignored = Git.cloneRepository()
                            .setURI("https://github.com/" + fullName + ".git")
                            .setDirectory(path.toFile())
                            .setCredentialsProvider(credentials)
                            .setCloneAllBranches(false)
                            .setBranch(branch)
                            .call()) {
                        // Token is supplied only through JGit's credentials provider.
                    }
                }
                try (Git git = Git.open(path.toFile())) {
                    Repository repository = git.getRepository();
                    String oldHead = repository.resolve("HEAD") == null ? "" : repository.resolve("HEAD").name();
                    if (!cloned) {
                        if (onProgress != null) onProgress.accept("正在拉取远端更新");
                    }
                    // Always refresh all remote heads so branch Q&A is possible even though
                    // CodeWiki indexes only the checked-out default branch working tree.
                    git.fetch()
                            .setRemote("origin")
                            .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                            .setCredentialsProvider(credentials)
                            .call();
                    if (onProgress != null) onProgress.accept("正在检出默认分支");
                    ObjectId remoteHead = repository.resolve("refs/remotes/origin/" + branch);
                    if (remoteHead == null) throw new IllegalStateException("远端默认分支不存在: " + branch);
                    git.checkout().setName(branch).setCreateBranch(
                            repository.findRef(branch) == null).setStartPoint("origin/" + branch).call();
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteHead.name()).call();
                    return new SyncResult(path, containerPath(repoId, ownerLogin), cloned, oldHead, remoteHead.name());
                }
            } catch (Exception ex) {
                throw new IllegalStateException("同步 Git 仓库失败: " + rootMessage(ex), ex);
            } finally {
                credentials.clear();
            }
        }
    }

    /**
     * List remote branches and compare each tip against the default branch.
     * CodeWiki indexes only the checked-out default branch; this metadata is for Q&A.
     */
    public List<Map<String, Object>> listBranches(String repoId, String ownerLogin, String defaultBranch) {
        String baseName = (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch.trim();
        try (Git git = Git.open(hostPath(repoId, ownerLogin).toFile())) {
            Repository repository = git.getRepository();
            // Best-effort refresh of remote heads (no credentials here; use last sync/fetch).
            try {
                git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                        .call();
            } catch (Exception ignored) {
                // Offline / auth missing: still answer from already-fetched refs.
            }

            ObjectId baseId = repository.resolve("refs/remotes/origin/" + baseName);
            if (baseId == null) {
                baseId = repository.resolve("refs/remotes/origin/master");
                if (baseId != null) {
                    baseName = "master";
                }
            }
            if (baseId == null && repository.resolve("HEAD") != null) {
                baseId = repository.resolve("HEAD");
            }
            if (baseId == null) {
                throw new IllegalStateException("无法解析默认分支 tip");
            }

            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            List<Map<String, Object>> result = new ArrayList<>();
            int index = 0;
            for (Ref ref : refs) {
                String full = ref.getName(); // refs/remotes/origin/foo
                if (!full.startsWith("refs/remotes/origin/")) {
                    continue;
                }
                String name = full.substring("refs/remotes/origin/".length());
                if ("HEAD".equals(name)) {
                    continue;
                }
                ObjectId tip = ref.getObjectId();
                if (tip == null) continue;

                int ahead = 0;
                int behind = 0;
                String tipMessage = "";
                String tipSha = tip.name();
                try (RevWalk walk = new RevWalk(repository)) {
                    RevCommit tipCommit = walk.parseCommit(tip);
                    tipMessage = tipCommit.getShortMessage();
                    RevCommit baseCommit = walk.parseCommit(baseId);
                    ahead = countReachable(walk, tipCommit, baseCommit);
                    behind = countReachable(walk, baseCommit, tipCommit);
                }

                boolean isDefault = name.equals(baseName);
                String relation;
                if (isDefault || (ahead == 0 && behind == 0)) {
                    relation = isDefault ? "default" : "same_as_default";
                } else if (ahead == 0 && behind > 0) {
                    relation = "behind_only";
                } else if (ahead > 0 && behind == 0) {
                    relation = "ahead_only";
                } else {
                    relation = "diverged";
                }

                // "几乎没有独有内容": no unique commits ahead of default
                boolean littleUniqueContent = !isDefault && ahead == 0;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", "git/branches/" + name);
                row.put("line", ++index);
                row.put("endLine", index);
                row.put("symbolName", name);
                row.put("symbolKind", "branch");
                row.put("score", 100 - index);
                row.put("retrievalType", "structured");
                row.put("sourceType", "branch_list");
                row.put("content", "branch: " + name
                        + "\ndefaultBranch: " + baseName
                        + "\nisDefault: " + isDefault
                        + "\ntipSha: " + tipSha.substring(0, Math.min(12, tipSha.length()))
                        + "\ntipMessage: " + tipMessage.replace('\n', ' ')
                        + "\naheadOfDefault: " + ahead
                        + "\nbehindDefault: " + behind
                        + "\nrelation: " + relation
                        + "\nlittleUniqueContent: " + littleUniqueContent
                        + "\nnote: CodeWiki 知识图谱只索引默认分支工作区；ahead/behind 相对 origin/"
                        + baseName + "。"
                        + "ahead=0 且 behind>0 → 落后默认分支；diverged → 与默认分支分叉/可能无关；"
                        + "same_as_default → 与默认分支同 tip。");
                result.add(row);
                if (result.size() >= 40) {
                    break;
                }
            }
            // Prepend a ranked digest so the LLM cannot invent ahead/behind numbers.
            if (!result.isEmpty()) {
                List<Map<String, Object>> ranked = new ArrayList<>(result);
                ranked.sort((a, b) -> {
                    int aa = extractInt(String.valueOf(a.get("content")), "aheadOfDefault: ");
                    int ba = extractInt(String.valueOf(b.get("content")), "aheadOfDefault: ");
                    if (aa != ba) return Integer.compare(ba, aa);
                    int ab = extractInt(String.valueOf(a.get("content")), "behindDefault: ");
                    int bb = extractInt(String.valueOf(b.get("content")), "behindDefault: ");
                    return Integer.compare(ab, bb);
                });
                StringBuilder digest = new StringBuilder();
                digest.append("分支相对 origin/").append(baseName).append(" 的真实 git 统计（勿改数字）\n");
                digest.append("说明: ahead=相对默认分支独有提交数；behind=默认分支有而本分支没有的提交数。\n");
                digest.append("littleUniqueContent=true 仅表示 ahead=0，不是「业务上没用」；")
                        .append("也不表示分支代码内容已被知识库索引。\n");
                digest.append("CodeWiki/GraphRAG 只索引默认分支工作区；下列数字来自本地 git fetch 后的 refs，")
                        .append("不是图谱检索结果。\n");
                digest.append("按 ahead 降序:\n");
                int rank = 0;
                for (Map<String, Object> row : ranked) {
                    String content = String.valueOf(row.get("content"));
                    String name = String.valueOf(row.get("symbolName"));
                    int ahead = extractInt(content, "aheadOfDefault: ");
                    int behind = extractInt(content, "behindDefault: ");
                    String relation = extractField(content, "relation: ");
                    digest.append(++rank).append(". ").append(name)
                            .append(" ahead=").append(ahead)
                            .append(" behind=").append(behind)
                            .append(" relation=").append(relation)
                            .append('\n');
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("file", "git/branches/_summary");
                summary.put("line", 0);
                summary.put("endLine", 0);
                summary.put("symbolName", "branch_rank_summary");
                summary.put("symbolKind", "summary");
                summary.put("score", 300);
                summary.put("retrievalType", "structured");
                summary.put("sourceType", "branch_list");
                summary.put("content", digest.toString());
                List<Map<String, Object>> withSummary = new ArrayList<>();
                withSummary.add(summary);
                withSummary.addAll(result);
                return withSummary;
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("读取分支列表失败: " + rootMessage(ex), ex);
        }
    }

    private static int countReachable(RevWalk walk, RevCommit start, RevCommit uninteresting) throws Exception {
        walk.reset();
        walk.setRetainBody(false);
        walk.markStart(start);
        walk.markUninteresting(uninteresting);
        int count = 0;
        for (RevCommit ignored : walk) {
            count++;
            if (count >= 500) {
                break;
            }
        }
        return count;
    }

    private static int extractInt(String content, String key) {
        String value = extractField(content, key);
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String extractField(String content, String key) {
        if (content == null || key == null) return "";
        int start = content.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = content.indexOf('\n', start);
        if (end < 0) end = content.length();
        return content.substring(start, end).trim();
    }

    public List<Map<String, Object>> history(String repoId, String ownerLogin, int limit) {
        try (Git git = Git.open(hostPath(repoId, ownerLogin).toFile())) {
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
                        + "\nparentCount: " + commit.getParentCount()
                        + "\nemptyChange: " + (changes.isEmpty() && commit.getParentCount() > 0)
                        + "\nchangedPaths: " + changes.stream().limit(20)
                                .map(change -> change.getChangeType() + ":" + change.getNewPath()).toList()
                        + "\nnote: emptyChange=true 表示相对父提交无文件差异（常见于空合并）；"
                        + "是否「对当前 HEAD 过时」需结合后续提交是否覆盖这些路径，证据不足时勿武断。");
                result.add(row);
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Git 历史失败: " + rootMessage(ex), ex);
        }
    }

    public Map<String, Object> compare(String repoId, String ownerLogin, String baseSha, String headSha) {
        try (Git git = Git.open(hostPath(repoId, ownerLogin).toFile());
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

    public Path hostPath(String repoId, String ownerLogin) {
        return Path.of(properties.hostRepoRoot()).toAbsolutePath().normalize()
                .resolve(safeName(ownerLogin)).resolve(safeRepoId(repoId));
    }

    private String containerPath(String repoId, String ownerLogin) {
        return Path.of(properties.containerRepoRoot()).resolve(safeName(ownerLogin)).resolve(safeRepoId(repoId))
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

    private String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record SyncResult(Path hostPath, String codeWikiPath, boolean cloned, String oldHead, String head) {}
}
