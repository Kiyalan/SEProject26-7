package com.repopilot.repository.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public KnowledgeStore(
            RepoIndexRepository repoIndexRepository,
            RepoIndexSettingsRepository settingsRepository,
            JdbcTemplate jdbc
    ) {
        this.repoIndexRepository = repoIndexRepository;
        this.settingsRepository = settingsRepository;
        this.jdbc = jdbc;
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

    public RepoIndex upsertIndex(String repoId, String fullName, String branch, String status, String ownerLogin) {
        RepoIndex index = repoIndexRepository.findById(repoId).orElseGet(RepoIndex::new);
        index.setRepoId(repoId);
        index.setOwnerLogin(ownerLogin);
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

    public List<RepoIndex> findByOwnerLogin(String ownerLogin) {
        return jdbc.query("SELECT * FROM repo_index WHERE owner_login = ?",
                (rs, rowNum) -> {
                    RepoIndex index = new RepoIndex();
                    index.setRepoId(rs.getString("repo_id"));
                    index.setOwnerLogin(rs.getString("owner_login"));
                    index.setFullName(rs.getString("full_name"));
                    index.setDefaultBranch(rs.getString("default_branch"));
                    index.setIndexedAt(rs.getString("indexed_at"));
                    index.setFileCount(rs.getInt("file_count"));
                    index.setChunkCount(rs.getInt("chunk_count"));
                    index.setStatus(rs.getString("status"));
                    index.setSummary(rs.getString("summary"));
                    index.setLanguages(rs.getString("languages"));
                    index.setReadmePath(rs.getString("readme_path"));
                    index.setCommitSha(rs.getString("commit_sha"));
                    index.setTopics(rs.getString("topics"));
                    index.setLicenseName(rs.getString("license_name"));
                    index.setReadmePreview(rs.getString("readme_preview"));
                    index.setActiveCommitSha(rs.getString("active_commit_sha"));
                    index.setQualityStatus(rs.getString("quality_status"));
                    index.setQualityScore(rs.getDouble("quality_score"));
                    index.setQualityReport(rs.getString("quality_report"));
                    index.setLastTaskId(rs.getString("last_task_id"));
                    index.setCodeWikiRepoId(rs.getString("codewiki_repo_id"));
                    index.setGraphNodeCount(rs.getInt("graph_node_count"));
                    index.setGraphEdgeCount(rs.getInt("graph_edge_count"));
                    index.setGraphCommunityCount(rs.getInt("graph_community_count"));
                    return index;
                }, ownerLogin);
    }

    public Optional<RepoIndexSettings> findSettingsByRepoIdAndOwner(String repoId, String ownerLogin) {
        List<RepoIndexSettings> results = jdbc.query(
                "SELECT * FROM repo_index_settings WHERE repo_id = ? AND owner_login = ?",
                (rs, rowNum) -> {
                    RepoIndexSettings s = new RepoIndexSettings();
                    s.setRepoId(rs.getString("repo_id"));
                    s.setIndexEachCommit(rs.getBoolean("index_each_commit"));
                    s.setMaxCommits(rs.getInt("max_commits"));
                    s.setActiveCommitSha(rs.getString("active_commit_sha"));
                    return s;
                }, repoId, ownerLogin);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
}
