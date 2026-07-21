package com.repopilot.repository.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.repopilot.entity.CommitChunk;
import com.repopilot.entity.CommitFile;
import com.repopilot.entity.FileContent;
import com.repopilot.entity.RepoCommit;
import com.repopilot.entity.RepoIndex;
import com.repopilot.entity.RepoIndexSettings;
import com.repopilot.entity.id.CommitChunkId;
import com.repopilot.entity.id.CommitFileId;
import com.repopilot.repository.CommitChunkRepository;
import com.repopilot.repository.CommitFileRepository;
import com.repopilot.repository.FileContentRepository;
import com.repopilot.repository.RepoCommitRepository;
import com.repopilot.repository.RepoIndexRepository;
import com.repopilot.repository.RepoIndexSettingsRepository;
import com.repopilot.util.JsonUtils;

@Component
public class KnowledgeStore {

    private final RepoIndexRepository repoIndexRepository;
    private final RepoIndexSettingsRepository settingsRepository;
    private final RepoCommitRepository commitRepository;
    private final CommitFileRepository fileRepository;
    private final CommitChunkRepository chunkRepository;
    private final FileContentRepository contentRepository;

    public KnowledgeStore(
            RepoIndexRepository repoIndexRepository,
            RepoIndexSettingsRepository settingsRepository,
            RepoCommitRepository commitRepository,
            CommitFileRepository fileRepository,
            CommitChunkRepository chunkRepository,
            FileContentRepository contentRepository
    ) {
        this.repoIndexRepository = repoIndexRepository;
        this.settingsRepository = settingsRepository;
        this.commitRepository = commitRepository;
        this.fileRepository = fileRepository;
        this.chunkRepository = chunkRepository;
        this.contentRepository = contentRepository;
    }

    public Optional<RepoIndex> findIndex(String repoId) {
        return repoIndexRepository.findById(repoId);
    }

    public RepoIndex saveIndex(RepoIndex index) {
        return repoIndexRepository.save(index);
    }

    @Transactional
    public void resetIndexingStatus(String repoId) {
        repoIndexRepository.resetIndexingStatus(repoId);
    }

    public Optional<RepoIndexSettings> findSettings(String repoId) {
        return settingsRepository.findById(repoId);
    }

    public RepoIndexSettings saveSettings(RepoIndexSettings settings) {
        return settingsRepository.save(settings);
    }

    public Optional<RepoCommit> findCommit(String repoId, String commitSha) {
        return commitRepository.findByRepoIdAndCommitSha(repoId, commitSha);
    }

    public RepoCommit saveCommit(RepoCommit commit) {
        return commitRepository.save(commit);
    }

    public List<RepoCommit> listCommits(String repoId) {
        return commitRepository.findByRepoIdOrderByCommittedAtDesc(repoId);
    }

    public Optional<String> findLatestIndexedCommitSha(String repoId) {
        return commitRepository.findFirstByRepoIdAndFileCountGreaterThanOrderByCommittedAtDesc(repoId, 0)
                .map(RepoCommit::getCommitSha);
    }

    public long countCommits(String repoId) {
        return commitRepository.countByRepoId(repoId);
    }

    @Transactional
    public void replaceCommitArtifacts(String repoId, String commitSha, List<CommitFile> files, List<CommitChunk> chunks) {
        fileRepository.deleteById_CommitSha(commitSha);
        chunkRepository.deleteById_CommitSha(commitSha);
        fileRepository.saveAll(files);
        chunkRepository.saveAll(chunks);
    }

    public List<CommitFile> listFiles(String commitSha) {
        return fileRepository.findById_CommitShaOrderById_PathAsc(commitSha);
    }

    public Map<String, String> fileHashes(String repoId, String commitSha) {
        Map<String, String> map = new LinkedHashMap<>();
        for (CommitFile file : fileRepository.findByRepoIdAndId_CommitShaAndFileType(repoId, commitSha, "file")) {
            map.put(file.getId().getPath(), file.getContentHash());
        }
        return map;
    }

    public List<CommitChunk> listChunks(String repoId, String commitSha) {
        return chunkRepository.findByRepoIdAndId_CommitSha(repoId, commitSha);
    }

    public String storeContent(String content, String hash) {
        if (!contentRepository.existsById(hash)) {
            FileContent entity = new FileContent();
            entity.setContentHash(hash);
            entity.setContent(content);
            contentRepository.save(entity);
        }
        return hash;
    }

    public Optional<String> getContent(String hash) {
        if (hash == null || hash.isBlank()) {
            return Optional.empty();
        }
        return contentRepository.findById(hash).map(FileContent::getContent);
    }

    public Map<String, Object> storageStats(String repoId) {
        return Map.of(
                "indexedCommits", countCommits(repoId),
                "uniqueFileBlobs", fileRepository.countDistinctContentHashByRepoId(repoId),
                "uniqueChunkBlobs", chunkRepository.countByRepoId(repoId),
                "totalBlobBytes", 0,
                "fileReferences", fileRepository.countByRepoIdAndFileType(repoId, "file")
        );
    }

    public RepoIndex upsertIndex(String repoId, String fullName, String branch, String status) {
        RepoIndex index = repoIndexRepository.findById(repoId).orElseGet(RepoIndex::new);
        index.setRepoId(repoId);
        index.setFullName(fullName);
        index.setDefaultBranch(branch);
        index.setStatus(status);
        return repoIndexRepository.save(index);
    }

    public RepoIndexSettings upsertSettings(String repoId, boolean indexEachCommit, int maxCommits, String activeCommitSha) {
        RepoIndexSettings settings = settingsRepository.findById(repoId).orElseGet(RepoIndexSettings::new);
        settings.setRepoId(repoId);
        settings.setIndexEachCommit(indexEachCommit);
        settings.setMaxCommits(maxCommits);
        if (activeCommitSha != null) {
            settings.setActiveCommitSha(activeCommitSha);
        }
        return settingsRepository.save(settings);
    }

    public RepoCommit upsertCommit(RepoCommit commit) {
        Optional<RepoCommit> existing = commitRepository.findByRepoIdAndCommitSha(commit.getRepoId(), commit.getCommitSha());
        if (existing.isPresent()) {
            RepoCommit current = existing.get();
            current.setParentSha(commit.getParentSha());
            current.setMessage(commit.getMessage());
            current.setAuthor(commit.getAuthor());
            current.setCommittedAt(commit.getCommittedAt());
            current.setIndexedAt(commit.getIndexedAt());
            current.setStatus(commit.getStatus());
            current.setSummary(commit.getSummary());
            current.setModuleSummary(commit.getModuleSummary());
            current.setLanguages(commit.getLanguages());
            current.setReadmePath(commit.getReadmePath());
            current.setReadmePreview(commit.getReadmePreview());
            current.setFileCount(commit.getFileCount());
            current.setChunkCount(commit.getChunkCount());
            return commitRepository.save(current);
        }
        return commitRepository.save(commit);
    }

    public static CommitFile newFile(String repoId, String commitSha, String path, String hash, String language, int size, String summary) {
        CommitFile file = new CommitFile();
        file.setId(new CommitFileId(commitSha, path));
        file.setRepoId(repoId);
        file.setContentHash(hash);
        file.setFileType("file");
        file.setSize(size);
        file.setLanguage(language);
        file.setSummary(summary);
        return file;
    }

    public static CommitChunk newChunk(String repoId, String commitSha, String filePath, int chunkIndex, String content, int startLine, byte[] embedding) {
        CommitChunk chunk = new CommitChunk();
        chunk.setId(new CommitChunkId(commitSha, filePath, chunkIndex));
        chunk.setRepoId(repoId);
        chunk.setContent(content);
        chunk.setStartLine(startLine);
        chunk.setEmbedding(embedding);
        return chunk;
    }

    public Map<String, Object> settingsView(String repoId) {
        Optional<RepoIndexSettings> row = settingsRepository.findById(repoId);
        String active = repoIndexRepository.findById(repoId).map(RepoIndex::getActiveCommitSha).orElse("");
        if (row.isEmpty()) {
            return Map.of("indexEachCommit", false, "maxCommits", 30, "activeCommitSha", active == null ? "" : active);
        }
        RepoIndexSettings settings = row.get();
        String resolvedActive = active != null && !active.isBlank() ? active : settings.getActiveCommitSha();
        return Map.of(
                "indexEachCommit", Boolean.TRUE.equals(settings.getIndexEachCommit()),
                "maxCommits", settings.getMaxCommits() == null ? 30 : settings.getMaxCommits(),
                "activeCommitSha", resolvedActive == null ? "" : resolvedActive
        );
    }

    public String messageOf(String repoId, String commitSha) {
        return findCommit(repoId, commitSha).map(RepoCommit::getMessage).orElse("");
    }

    public List<String> topics(RepoIndex index) {
        return index == null ? List.of() : JsonUtils.parseStringList(index.getTopics());
    }
}
