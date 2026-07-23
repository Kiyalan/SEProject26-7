package com.repopilot.repository.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.repopilot.entity.RepoIndex;
import com.repopilot.entity.RepoIndexSettings;
import com.repopilot.repository.RepoIndexRepository;
import com.repopilot.repository.RepoIndexSettingsRepository;
import com.repopilot.util.JsonUtils;

@Component
public class KnowledgeStore {

    private final RepoIndexRepository repoIndexRepository;
    private final RepoIndexSettingsRepository settingsRepository;

    public KnowledgeStore(
            RepoIndexRepository repoIndexRepository,
            RepoIndexSettingsRepository settingsRepository
    ) {
        this.repoIndexRepository = repoIndexRepository;
        this.settingsRepository = settingsRepository;
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

    public List<String> topics(RepoIndex index) {
        return index == null ? List.of() : JsonUtils.parseStringList(index.getTopics());
    }
}
