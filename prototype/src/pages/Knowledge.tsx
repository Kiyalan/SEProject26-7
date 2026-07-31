import { FileIcon, FileDirectoryIcon, GitCommitIcon, PackageIcon, SyncIcon } from '@primer/octicons-react'
import { Alert, Checkbox, InputNumber, Spin, Tree } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { useCallback, useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import PortfolioPanel from '../components/PortfolioPanel'
import { useRepoContext } from '../context/RepoContext'
import {
  buildKnowledge,
  compareKnowledgeCommits,
  fetchKnowledge,
  fetchKnowledgePolicy,
  updateKnowledgeSettings,
  type CommitCompareResult,
  type IndexedCommit,
  type KnowledgeOverview,
} from '../lib/api'
import type { KnowledgeNode } from '../types'

function toTreeData(nodes: KnowledgeNode[]): DataNode[] {
  return nodes.map((node) => ({
    key: node.key,
    title: node.title,
    icon:
      node.type === 'folder' ? (
        <FileDirectoryIcon size={14} />
      ) : node.type === 'module' ? (
        <PackageIcon size={14} />
      ) : (
        <FileIcon size={14} />
      ),
    children: node.children ? toTreeData(node.children) : undefined,
  }))
}

export default function Knowledge() {
  const { repoId, setRepoId, repos } = useRepoContext()
  const [loading, setLoading] = useState(false)
  const [building, setBuilding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [overview, setOverview] = useState<KnowledgeOverview | null>(null)
  const [indexEachCommit, setIndexEachCommit] = useState(false)
  const [maxCommits, setMaxCommits] = useState(30)
  const [selectedCommit, setSelectedCommit] = useState('')
  const [compareBase, setCompareBase] = useState('')
  const [compareHead, setCompareHead] = useState('')
  const [compareResult, setCompareResult] = useState<CommitCompareResult | null>(null)
  const [comparing, setComparing] = useState(false)
  const [policy, setPolicy] = useState<Awaited<ReturnType<typeof fetchKnowledgePolicy>> | null>(null)

  const loadOverview = useCallback(
    async (id: string, commitSha?: string) => {
      if (!id) return
      setLoading(true)
      setError(null)
      try {
        const data = await fetchKnowledge(id, commitSha)
        setOverview(data)
        setIndexEachCommit(data.settings?.indexEachCommit ?? false)
        setMaxCommits(data.settings?.maxCommits ?? 30)
        const active = commitSha || data.commitSha || data.settings?.activeCommitSha || ''
        setSelectedCommit(active)
        const commits = data.commits || []
        if (commits.length >= 2) {
          setCompareBase(commits[1].commitSha)
          setCompareHead(commits[0].commitSha)
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载失败')
      } finally {
        setLoading(false)
      }
    },
    [],
  )

  useEffect(() => {
    if (repoId) {
      loadOverview(repoId)
      fetchKnowledgePolicy(repoId)
        .then(setPolicy)
        .catch(() => setPolicy(null))
    }
  }, [repoId, loadOverview])

  const handleSelectCommit = async (commit: IndexedCommit) => {
    if (!repoId) return
    setSelectedCommit(commit.commitSha)
    await updateKnowledgeSettings(repoId, { activeCommitSha: commit.commitSha })
    await loadOverview(repoId, commit.commitSha)
  }

  const handleBuild = async () => {
    if (!repoId) return
    setBuilding(true)
    setError(null)
    try {
      await buildKnowledge(repoId, { indexEachCommit, maxCommits })
      await updateKnowledgeSettings(repoId, { indexEachCommit, maxCommits })
      await loadOverview(repoId)
    } catch (err) {
      setError(err instanceof Error ? err.message : '构建失败')
    } finally {
      setBuilding(false)
    }
  }

  const handleCompare = async () => {
    if (!repoId || !compareBase || !compareHead) return
    setComparing(true)
    setError(null)
    try {
      const result = await compareKnowledgeCommits(repoId, compareBase, compareHead)
      setCompareResult(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : '对比失败')
    } finally {
      setComparing(false)
    }
  }

  const langEntries = Object.entries(overview?.languages || {})
  const langTotal = langEntries.reduce((s, [, c]) => s + c, 0) || 1
  const commits = overview?.commits || []

  return (
    <PageShell
      title="知识库"
      description="按 commit 构建多版本索引，相同内容自动去重存储，支持历史对比"
      actions={
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <select
            className="gh-btn"
            value={repoId}
            onChange={(e) => setRepoId(e.target.value)}
            style={{ minWidth: 180 }}
          >
            {repos.map((r) => (
              <option key={r.id} value={r.id}>
                {r.fullName}
              </option>
            ))}
          </select>
          <button type="button" className="gh-btn gh-btn-primary" disabled={building} onClick={handleBuild}>
            <SyncIcon size={14} />
            {building ? '构建中…' : indexEachCommit ? `索引最近 ${maxCommits} 个 commit` : '索引 HEAD'}
          </button>
        </div>
      }
    >
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

      <PortfolioPanel />

      <div className="gh-box" style={{ marginBottom: 16 }}>
        <div className="gh-box-header">索引策略</div>
        <div className="gh-box-body">
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'center' }}>
            <Checkbox checked={indexEachCommit} onChange={(e) => setIndexEachCommit(e.target.checked)}>
              为每个 commit 分别构建知识库（保留完整历史切片）
            </Checkbox>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="gh-muted">最多索引</span>
              <InputNumber min={1} max={100} value={maxCommits} onChange={(v) => setMaxCommits(v ?? 30)} />
              <span className="gh-muted">个 commit</span>
            </label>
          </div>
          <p className="gh-muted" style={{ margin: '12px 0 0', fontSize: 12 }}>
            存储采用内容寻址：文件与片段按 SHA-256 哈希去重，各 commit 仅保存引用，相同内容只占一份空间。
          </p>
          {overview?.deduplication && (
            <div style={{ marginTop: 12, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              <span className="gh-label">已索引 {overview.deduplication.indexedCommits} 个 commit</span>
              <span className="gh-label">唯一文件 blob {overview.deduplication.uniqueFileBlobs}</span>
              <span className="gh-label">唯一片段 blob {overview.deduplication.uniqueChunkBlobs}</span>
              <span className="gh-label">文件引用 {overview.deduplication.fileReferences}</span>
            </div>
          )}
        </div>
      </div>

      {overview?.status === 'not_indexed' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="尚未构建知识库"
          description="勾选「为每个 commit 分别构建」可保留每次提交的快照；未勾选则仅索引当前 HEAD。"
        />
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      ) : (
        <>
          {commits.length > 0 && (
            <div className="gh-grid-2" style={{ marginBottom: 16 }}>
              <div className="gh-box">
                <div className="gh-box-header">
                  <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <GitCommitIcon size={16} />
                    Commit 时间线
                  </span>
                  <span className="gh-muted" style={{ fontWeight: 400 }}>
                    {commits.length} 个版本
                  </span>
                </div>
                <div className="gh-box-body" style={{ maxHeight: 320, overflowY: 'auto' }}>
                  {commits.map((c) => (
                    <button
                      key={c.commitSha}
                      type="button"
                      className={`gh-commit-item${selectedCommit === c.commitSha ? ' active' : ''}`}
                      onClick={() => handleSelectCommit(c)}
                    >
                      <div style={{ fontFamily: 'monospace', fontSize: 12 }}>{c.shortSha}</div>
                      <div style={{ fontWeight: 500, margin: '4px 0' }}>{c.message || '（无说明）'}</div>
                      <div className="gh-muted" style={{ fontSize: 12 }}>
                        {c.author} · {c.committedAt} · {c.fileCount} 文件
                      </div>
                    </button>
                  ))}
                </div>
              </div>

              <div className="gh-box">
                <div className="gh-box-header">历史对比</div>
                <div className="gh-box-body">
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
                    <label>
                      <span className="gh-muted" style={{ fontSize: 12 }}>基准 (旧)</span>
                      <select
                        className="gh-btn"
                        style={{ width: '100%', marginTop: 4 }}
                        value={compareBase}
                        onChange={(e) => setCompareBase(e.target.value)}
                      >
                        {commits.map((c) => (
                          <option key={c.commitSha} value={c.commitSha}>
                            {c.shortSha} — {c.message.slice(0, 40)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span className="gh-muted" style={{ fontSize: 12 }}>对比 (新)</span>
                      <select
                        className="gh-btn"
                        style={{ width: '100%', marginTop: 4 }}
                        value={compareHead}
                        onChange={(e) => setCompareHead(e.target.value)}
                      >
                        {commits.map((c) => (
                          <option key={c.commitSha} value={c.commitSha}>
                            {c.shortSha} — {c.message.slice(0, 40)}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                  <button
                    type="button"
                    className="gh-btn gh-btn-primary"
                    style={{ width: '100%' }}
                    disabled={comparing || !compareBase || !compareHead}
                    onClick={handleCompare}
                  >
                    {comparing ? '对比中…' : '对比两个版本'}
                  </button>

                  {compareResult && (
                    <div style={{ marginTop: 16 }}>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 }}>
                        <span className="gh-label gh-label-green">+{compareResult.added.length} 新增</span>
                        <span className="gh-label gh-label-red">-{compareResult.removed.length} 删除</span>
                        <span className="gh-label gh-label-orange">~{compareResult.modified.length} 修改</span>
                        <span className="gh-label">{compareResult.unchanged} 未变</span>
                        <span className="gh-label gh-label-blue">{compareResult.sharedBlobCount} 共用 blob</span>
                      </div>
                      {compareResult.previews.map((p) => (
                        <div key={p.path} style={{ marginBottom: 12 }}>
                          <strong style={{ fontSize: 13 }}>{p.path}</strong>
                          <pre className="gh-diff-pre">{p.diff || '（无 diff 预览）'}</pre>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          <div className="gh-grid-2" style={{ marginBottom: 16 }}>
            <div className="gh-box">
              <div className="gh-box-header">
                仓库摘要
                {overview?.shortSha && (
                  <span className="gh-label gh-muted">@{overview.shortSha}</span>
                )}
              </div>
              <div className="gh-box-body">
                {overview?.summary ? (
                  <p style={{ margin: 0, lineHeight: 1.6 }}>{overview.summary}</p>
                ) : (
                  <p className="gh-muted" style={{ margin: 0 }}>构建索引后自动生成</p>
                )}
              </div>
            </div>
            <div className="gh-box">
              <div className="gh-box-header">README 预览</div>
              <div className="gh-box-body">
                {overview?.readmePreview ? (
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 13, maxHeight: 200, overflow: 'auto' }}>
                    {overview.readmePreview}
                  </pre>
                ) : (
                  <p className="gh-muted" style={{ margin: 0 }}>构建后显示</p>
                )}
              </div>
            </div>
          </div>

          <div className="gh-grid-2">
            <div className="gh-box">
              <div className="gh-box-header">
                项目结构
                {overview && (
                  <span className="gh-muted" style={{ fontWeight: 400 }}>
                    {overview.fileCount} 文件 · {overview.chunkCount} 片段
                  </span>
                )}
              </div>
              <div className="gh-box-body">
                {overview?.tree?.length ? (
                  <Tree showIcon defaultExpandAll treeData={toTreeData(overview.tree)} />
                ) : (
                  <p className="gh-muted" style={{ margin: 0 }}>暂无索引数据</p>
                )}
              </div>
            </div>
            <div>
              <div className="gh-box">
                <div className="gh-box-header">语言分布</div>
                <div className="gh-box-body">
                  {langEntries.length ? (
                    langEntries.map(([lang, count]) => (
                      <div key={lang} className="gh-data-row">
                        <span>{lang}</span>
                        <span className="gh-muted">
                          {count} · {Math.round((count / langTotal) * 100)}%
                        </span>
                      </div>
                    ))
                  ) : (
                    <p className="gh-muted" style={{ margin: 0 }}>构建索引后显示</p>
                  )}
                </div>
              </div>
              <div className="gh-box">
                <div className="gh-box-header">索引范围说明</div>
                <div className="gh-box-body">
                  {policy ? (
                    <>
                      <p className="gh-muted" style={{ margin: '0 0 8px', fontSize: 13 }}>
                        <strong>必须索引</strong>（服务问答 / Issue）：{policy.required.join('、')}
                      </p>
                      <p className="gh-muted" style={{ margin: '0 0 8px', fontSize: 13 }}>
                        <strong>不索引</strong>：{policy.excludedDirs.join('、')} 等目录
                      </p>
                      <p className="gh-muted" style={{ margin: '0 0 8px', fontSize: 12 }}>
                        <strong>仅存库不展示</strong>：{policy.storeOnly.join('；')}
                      </p>
                      {Object.entries(policy.featureMatrix).map(([feature, row]) => (
                        <div key={feature} style={{ marginTop: 10, fontSize: 12 }}>
                          <strong>{feature}</strong>
                          <div className="gh-muted">需要：{row.needs.join('、')}</div>
                          <div className="gh-muted">不必：{row.not_needed.join('、')}</div>
                        </div>
                      ))}
                    </>
                  ) : (
                    <p className="gh-muted" style={{ margin: 0 }}>加载策略说明…</p>
                  )}
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </PageShell>
  )
}
