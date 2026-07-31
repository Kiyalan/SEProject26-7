package com.repopilot.repository.support;

import com.repopilot.entity.RepoIndex;
import com.repopilot.entity.RepoIndexSettings;
import com.repopilot.repository.RepoIndexRepository;
import com.repopilot.repository.RepoIndexSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeStoreTest {

    @Mock
    private RepoIndexRepository repoIndexRepository;

    @Mock
    private RepoIndexSettingsRepository settingsRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private KnowledgeStore knowledgeStore;

    @BeforeEach
    void setUp() {
        knowledgeStore = new KnowledgeStore(repoIndexRepository, settingsRepository, jdbcTemplate);
    }

    @Test
    void findIndex_returnsFromRepository() {
        RepoIndex index = new RepoIndex();
        index.setRepoId("repo123");
        when(repoIndexRepository.findById("repo123")).thenReturn(Optional.of(index));

        Optional<RepoIndex> result = knowledgeStore.findIndex("repo123");

        assertThat(result).isPresent();
        assertThat(result.get().getRepoId()).isEqualTo("repo123");
    }

    @Test
    void findIndex_returnsEmptyWhenNotFound() {
        when(repoIndexRepository.findById("unknown")).thenReturn(Optional.empty());

        Optional<RepoIndex> result = knowledgeStore.findIndex("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void saveIndex_savesToRepository() {
        RepoIndex index = new RepoIndex();
        index.setRepoId("repo123");
        when(repoIndexRepository.save(index)).thenReturn(index);

        RepoIndex result = knowledgeStore.saveIndex(index);

        assertThat(result.getRepoId()).isEqualTo("repo123");
        verify(repoIndexRepository).save(index);
    }

    @Test
    void resetIndexingStatus_callsRepository() {
        knowledgeStore.resetIndexingStatus("repo123");

        verify(repoIndexRepository).resetIndexingStatus("repo123");
    }

    @Test
    void findSettings_returnsFromRepository() {
        RepoIndexSettings settings = new RepoIndexSettings();
        settings.setRepoId("repo123");
        when(settingsRepository.findById("repo123")).thenReturn(Optional.of(settings));

        Optional<RepoIndexSettings> result = knowledgeStore.findSettings("repo123");

        assertThat(result).isPresent();
        assertThat(result.get().getRepoId()).isEqualTo("repo123");
    }

    @Test
    void saveSettings_savesToRepository() {
        RepoIndexSettings settings = new RepoIndexSettings();
        settings.setRepoId("repo123");
        when(settingsRepository.save(settings)).thenReturn(settings);

        RepoIndexSettings result = knowledgeStore.saveSettings(settings);

        assertThat(result.getRepoId()).isEqualTo("repo123");
        verify(settingsRepository).save(settings);
    }

    @Test
    void upsertIndex_createsNewWhenNotExists() {
        when(repoIndexRepository.findById("new-repo")).thenReturn(Optional.empty());
        when(repoIndexRepository.save(any(RepoIndex.class))).thenAnswer(i -> i.getArgument(0));

        RepoIndex result = knowledgeStore.upsertIndex("new-repo", "owner/new-repo", "main", "idle", "owner");

        assertThat(result.getRepoId()).isEqualTo("new-repo");
        assertThat(result.getOwnerLogin()).isEqualTo("owner");
        assertThat(result.getFullName()).isEqualTo("owner/new-repo");
    }

    @Test
    void upsertIndex_updatesExisting() {
        RepoIndex existing = new RepoIndex();
        existing.setRepoId("existing-repo");
        existing.setFullName("owner/existing-repo");
        when(repoIndexRepository.findById("existing-repo")).thenReturn(Optional.of(existing));
        when(repoIndexRepository.save(any(RepoIndex.class))).thenAnswer(i -> i.getArgument(0));

        RepoIndex result = knowledgeStore.upsertIndex("existing-repo", "owner/updated-repo", "develop", "ready", "owner");

        assertThat(result.getRepoId()).isEqualTo("existing-repo");
        assertThat(result.getFullName()).isEqualTo("owner/updated-repo");
        assertThat(result.getDefaultBranch()).isEqualTo("develop");
        assertThat(result.getStatus()).isEqualTo("ready");
    }

    @Test
    void upsertSettings_createsNewWhenNotExists() {
        when(settingsRepository.findById("new-repo")).thenReturn(Optional.empty());
        when(settingsRepository.save(any(RepoIndexSettings.class))).thenAnswer(i -> i.getArgument(0));

        RepoIndexSettings result = knowledgeStore.upsertSettings("new-repo", true, 50, "abc123");

        assertThat(result.getRepoId()).isEqualTo("new-repo");
        assertThat(result.getIndexEachCommit()).isTrue();
        assertThat(result.getMaxCommits()).isEqualTo(50);
        assertThat(result.getActiveCommitSha()).isEqualTo("abc123");
    }

    @Test
    void upsertSettings_updatesExisting() {
        RepoIndexSettings existing = new RepoIndexSettings();
        existing.setRepoId("existing-repo");
        existing.setMaxCommits(30);
        when(settingsRepository.findById("existing-repo")).thenReturn(Optional.of(existing));
        when(settingsRepository.save(any(RepoIndexSettings.class))).thenAnswer(i -> i.getArgument(0));

        RepoIndexSettings result = knowledgeStore.upsertSettings("existing-repo", false, 100, "def456");

        assertThat(result.getRepoId()).isEqualTo("existing-repo");
        assertThat(result.getIndexEachCommit()).isFalse();
        assertThat(result.getMaxCommits()).isEqualTo(100);
        assertThat(result.getActiveCommitSha()).isEqualTo("def456");
    }

    @Test
    void upsertSettings_doesNotOverwriteNullActiveCommit() {
        RepoIndexSettings existing = new RepoIndexSettings();
        existing.setRepoId("repo");
        existing.setActiveCommitSha("original-sha");
        when(settingsRepository.findById("repo")).thenReturn(Optional.of(existing));
        when(settingsRepository.save(any(RepoIndexSettings.class))).thenAnswer(i -> i.getArgument(0));

        RepoIndexSettings result = knowledgeStore.upsertSettings("repo", false, 30, null);

        assertThat(result.getActiveCommitSha()).isEqualTo("original-sha");
    }

    @Test
    void settingsView_returnsDefaultsWhenNotFound() {
        when(settingsRepository.findById("unknown")).thenReturn(Optional.empty());
        RepoIndex repoIndex = new RepoIndex();
        repoIndex.setActiveCommitSha(null);
        when(repoIndexRepository.findById("unknown")).thenReturn(Optional.of(repoIndex));

        var result = knowledgeStore.settingsView("unknown");

        assertThat(result.get("indexEachCommit")).isEqualTo(false);
        assertThat(result.get("maxCommits")).isEqualTo(30);
        assertThat(result.get("activeCommitSha")).isEqualTo("");
    }

    @Test
    void settingsView_returnsStoredSettings() {
        RepoIndexSettings settings = new RepoIndexSettings();
        settings.setRepoId("repo123");
        settings.setIndexEachCommit(true);
        settings.setMaxCommits(50);
        settings.setActiveCommitSha("sha123");
        when(settingsRepository.findById("repo123")).thenReturn(Optional.of(settings));
        RepoIndex repoIndex = new RepoIndex();
        repoIndex.setActiveCommitSha("sha123");
        when(repoIndexRepository.findById("repo123")).thenReturn(Optional.of(repoIndex));

        var result = knowledgeStore.settingsView("repo123");

        assertThat(result.get("indexEachCommit")).isEqualTo(true);
        assertThat(result.get("maxCommits")).isEqualTo(50);
        assertThat(result.get("activeCommitSha")).isEqualTo("sha123");
    }

    @Test
    void settingsView_usesRepoActiveWhenSettingsEmpty() {
        RepoIndexSettings settings = new RepoIndexSettings();
        settings.setRepoId("repo123");
        settings.setMaxCommits(30);
        when(settingsRepository.findById("repo123")).thenReturn(Optional.of(settings));
        RepoIndex repoIndex = new RepoIndex();
        repoIndex.setActiveCommitSha("repo-sha");
        when(repoIndexRepository.findById("repo123")).thenReturn(Optional.of(repoIndex));

        var result = knowledgeStore.settingsView("repo123");

        assertThat(result.get("activeCommitSha")).isEqualTo("repo-sha");
    }

    @Test
    void topics_returnsEmptyListForNull() {
        List<String> result = knowledgeStore.topics(null);
        assertThat(result).isEmpty();
    }

    @Test
    void topics_parsesFromIndex() {
        RepoIndex index = new RepoIndex();
        index.setTopics("[\"java\", \"spring\", \"web\"]");

        List<String> result = knowledgeStore.topics(index);

        assertThat(result).containsExactly("java", "spring", "web");
    }

    @Test
    void findByOwnerLogin_returnsFromJdbc() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("owner"))).thenReturn(List.of());

        var result = knowledgeStore.findByOwnerLogin("owner");

        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq("owner"));
    }

    @Test
    void findSettingsByRepoIdAndOwner_returnsFromJdbc() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("repo123"), eq("owner")))
                .thenReturn(List.of());

        var result = knowledgeStore.findSettingsByRepoIdAndOwner("repo123", "owner");

        assertThat(result).isEmpty();
    }
}
