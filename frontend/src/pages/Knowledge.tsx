import { FileIcon, FileDirectoryIcon, GitCommitIcon, PackageIcon, SyncIcon } from '@primer/octicons-react'
import { Alert, Modal, Spin, message } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { Tree } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import PortfolioPanel from '../components/PortfolioPanel'
import { useRepoContext } from '../context/RepoContext'
import {
  buildKnowledge,
  resetKnowledge,
  compareKnowledgeCommits,
  exportRepoFaq,
  fetchKnowledge,
  fetchKnowledgeBuildErrors,
  fetchKnowledgeBuildTasks,
  fetchKnowledgeGraphStatus,
  fetchKnowledgePolicy,
  fetchKnowledgeWiki,
  fetchRepoFaq,
  generateKnowledgeWiki,
  generateRepoFaq,
  type CommitCompareResult,
  type FaqItem,
  type FaqListResponse,
  type IndexedCommit,
  type KnowledgeBuildError,
  type KnowledgeBuildTask,
  type KnowledgeGraphStatus,
  type KnowledgeOverview,
  type KnowledgePolicy,
  type KnowledgeWiki,
} from '../api/generated'
import type { KnowledgeNode } from '../lib/FrontendTypes'
import { fetchRepoProgress, sleep } from '../lib/progress'

const faqCategoryLabels: Record<string, string> = {
  overview: '概览',
  getting_started: '入门',
  api: '接口',
  deployment: '部署',
  architecture: '架构',
  troubleshooting: '排查',
}

interface BuildPhase {
  key: string
  label: string
}

const BUILD_PHASES: BuildPhase[] = [
  { key: 'preparing', label: '准备' },
  { key: 'git_sync', label: '同步仓库' },
  { key: 'register', label: '注册' },
  { key: 'analyze', label: '源码分析' },
  { key: 'graphrag', label: '构建图谱' },
  { key: 'update', label: '增量更新' },
  { key: 'indexing', label: '索引元数据' },
  { key: 'quality', label: '质量评分' },
]

function getPhaseStatus(phaseKey: string, currentStage: string, buildProgress: number): 'done' | 'active' | 'pending' {
  const currentIdx = BUILD_PHASES.findIndex((p) => p.key === currentStage)
  const phaseIdx = BUILD_PHASES.findIndex((p) => p.key === phaseKey)
  if (phaseIdx < currentIdx || (buildProgress >= 100 && phaseIdx <= currentIdx)) return 'done'
  if (phaseIdx === currentIdx && buildProgress > 0) return 'active'
  if (currentIdx < 0 && buildProgress >= 100) return 'done'
  return 'pending'
}

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
  const { currentRepoId, setCurrentRepo, repoList } = useRepoContext()
  const [loading, setLoading] = useState(false)
  const [building, setBuilding] = useState(false)
  const [buildMessage, setBuildMessage] = useState('')
  const [buildProgress, setBuildProgress] = useState(0)
  const [buildStage, setBuildStage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [overview, setOverview] = useState<KnowledgeOverview | null>(null)
  const [selectedCommit, setSelectedCommit] = useState('')
  const [compareBase, setCompareBase] = useState('')
  const [compareHead, setCompareHead] = useState('')
  const [compareResult, setCompareResult] = useState<CommitCompareResult | null>(null)
  const [comparing, setComparing] = useState(false)
  const [policy, setPolicy] = useState<KnowledgePolicy | null>(null)
  const [graphStatus, setGraphStatus] = useState<KnowledgeGraphStatus | null>(null)
  const [wiki, setWiki] = useState<KnowledgeWiki | null>(null)
  const [selectedWikiPageId, setSelectedWikiPageId] = useState('')
  const [generatingWiki, setGeneratingWiki] = useState(false)
  const [buildTasks, setBuildTasks] = useState<KnowledgeBuildTask[]>([])
  const [selectedTaskId, setSelectedTaskId] = useState('')
  const [taskErrors, setTaskErrors] = useState<KnowledgeBuildError[]>([])
  const [faq, setFaq] = useState<FaqListResponse | null>(null)
  const [generatingFaq, setGeneratingFaq] = useState(false)
  const [selectedFaqId, setSelectedFaqId] = useState('')

  const loadTasks = useCallback(async (id: string) => {
    try {
      const { data } = await fetchKnowledgeBuildTasks({
        path: { repoId: id },
        query: { limit: 10 },
      })
      setBuildTasks(data.items)
      const first = data.items[0]
      if (first) {
        setSelectedTaskId((prev) => prev || first.taskId)
      }
    } catch {
      setBuildTasks([])
    }
  }, [])

  const loadFaq = useCallback(async (id: string) => {
    try {
      const { data } = await fetchRepoFaq({ path: { repoId: id } })
      setFaq(data)
      setSelectedFaqId(data.items[0]?.id ?? '')
    } catch {
      setFaq(null)
      setSelectedFaqId('')
    }
  }, [])

  const loadOverview = useCallback(
    async (id: string, commitSha?: string) => {
      if (!id) return
      setLoading(true)
      setError(null)
      try {
        const { data } = await fetchKnowledge({
          path: { repoId: id },
          query: commitSha ? { commit: commitSha } : undefined,
        })
        setOverview(data)
        const active = commitSha || data.commitSha || data.settings?.activeCommitSha || ''
        setSelectedCommit(active)
        const commits = data.commits || []
        if (commits.length >= 2) {
          setCompareBase(commits[1].commitSha)
          setCompareHead(commits[0].commitSha)
        }
      } catch (err) {
        const raw = err instanceof Error ? err.message : '加载失败'
        const friendly = /Unexpected end of file|EOF|ECONNRESET|Failed to fetch/i.test(raw)
          ? '知识库概览响应中断（常见于文件树过大或 CodeWiki 暂不可用）。已尽量展示其它面板，可刷新或重新构建。'
          : raw
        setError(friendly)
      } finally {
        setLoading(false)
      }
    },
    [],
  )

  useEffect(() => {
    if (currentRepoId) {
      loadOverview(currentRepoId)
      loadTasks(currentRepoId)
      loadFaq(currentRepoId)
      fetchKnowledgePolicy({ path: { repoId: currentRepoId } })
        .then(({ data }) => setPolicy(data))
        .catch(() => setPolicy(null))
      fetchKnowledgeGraphStatus({ path: { repoId: currentRepoId } })
        .then(({ data }) => setGraphStatus(data))
        .catch(() => setGraphStatus(null))
      fetchKnowledgeWiki({ path: { repoId: currentRepoId }, query: { language: 'zh' } })
        .then(({ data }) => {
          setWiki(data)
          setSelectedWikiPageId(data.pages[0]?.id ?? '')
        })
        .catch(() => {
          setWiki(null)
          setSelectedWikiPageId('')
        })
    }
  }, [currentRepoId, loadOverview, loadTasks, loadFaq])

  useEffect(() => {
    if (!currentRepoId || !selectedTaskId) {
      setTaskErrors([])
      return
    }
    fetchKnowledgeBuildErrors({
      path: { repoId: currentRepoId, taskId: selectedTaskId },
    })
      .then(({ data }) => setTaskErrors(data.items))
      .catch(() => setTaskErrors([]))
  }, [currentRepoId, selectedTaskId])

  const handleSelectCommit = (commit: IndexedCommit) => {
    setSelectedCommit(commit.commitSha)
  }

  const handleBuild = async () => {
    if (!currentRepoId) return
    setBuilding(true)
    setBuildMessage('正在启动构建…')
    setBuildProgress(0)
    setBuildStage('')
    setError(null)

    let pollTimer: ReturnType<typeof setInterval> | null = null
    const stopPolling = () => {
      if (pollTimer) {
        clearInterval(pollTimer)
        pollTimer = null
      }
    }

    pollTimer = setInterval(async () => {
      try {
        const snapshot = await fetchRepoProgress(currentRepoId)
        const knowledge = snapshot.knowledge
        setBuildMessage(knowledge.message || knowledge.status)
        setBuildProgress(knowledge.progress ?? 0)
        setBuildStage(knowledge.stage ?? '')
        if (knowledge.status === 'done' || knowledge.status === 'error') {
          stopPolling()
          if (knowledge.status === 'error') {
            setError(knowledge.message || '构建失败')
            setBuilding(false)
          }
        }
      } catch {
        // 轮询失败时不打断构建
      }
    }, 1500)

    try {
      const { data } = await buildKnowledge({
        path: { repoId: currentRepoId },
      })

      const startedAsync = Boolean((data as { async?: boolean } | undefined)?.async)
      if (startedAsync) {
        while (true) {
          const snapshot = await fetchRepoProgress(currentRepoId)
          const knowledge = snapshot.knowledge
          setBuildMessage(knowledge.message || '构建中…')
          setBuildProgress(knowledge.progress ?? 0)
          setBuildStage(knowledge.stage ?? '')
          if (knowledge.status === 'done') {
            break
          }
          if (knowledge.status === 'error') {
            throw new Error(knowledge.message || '构建失败')
          }
          if (knowledge.status === 'idle') {
            await sleep(1500)
            continue
          }
          await sleep(1500)
        }
      }

      await loadOverview(currentRepoId)
      await loadTasks(currentRepoId)
      fetchKnowledgeGraphStatus({ path: { repoId: currentRepoId } })
        .then(({ data: status }) => setGraphStatus(status))
        .catch(() => setGraphStatus(null))
    } catch (err) {
      setError(err instanceof Error ? err.message : '构建失败')
      if (currentRepoId) await loadTasks(currentRepoId)
    } finally {
      stopPolling()
      setBuilding(false)
      setBuildMessage('')
      setBuildProgress(0)
      setBuildStage('')
    }
  }

  const handleReset = async () => {
    if (!currentRepoId) return
    Modal.confirm({
      title: '确认重置知识库？',
      content:
        '将删除本地索引、FAQ、构建记录，并尝试删除 CodeWiki 中的图谱/Wiki。之后需要重新「构建知识库」。',
      okText: '确认重置',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        setError(null)
        try {
          const { data } = await resetKnowledge({ path: { repoId: currentRepoId } })
          const payload = data as {
            message?: string
            codeWikiDeleted?: boolean
            codeWikiWarning?: string
          }
          if (payload.codeWikiWarning) {
            message.warning(payload.message || '本地已重置，CodeWiki 清理不完整')
          } else {
            message.success(payload.message || '知识库已重置')
          }
          setOverview(null)
          setGraphStatus(null)
          setWiki(null)
          setSelectedWikiPageId('')
          setFaq(null)
          setTasks([])
          setTaskErrors([])
          await loadOverview(currentRepoId)
          await loadTasks(currentRepoId)
          await loadFaq(currentRepoId)
          fetchKnowledgeGraphStatus({ path: { repoId: currentRepoId } })
            .then(({ data: status }) => setGraphStatus(status))
            .catch(() => setGraphStatus(null))
          fetchKnowledgeWiki({ path: { repoId: currentRepoId }, query: { language: 'zh' } })
            .then(({ data: current }) => setWiki(current))
            .catch(() => setWiki(null))
        } catch (err) {
          setError(err instanceof Error ? err.message : '重置失败')
        }
      },
    })
  }

  const handleCompare = async () => {
    if (!currentRepoId || !compareBase || !compareHead) return
    setComparing(true)
    setError(null)
    try {
      const { data: result } = await compareKnowledgeCommits({
        path: { repoId: currentRepoId },
        query: { base: compareBase, head: compareHead },
      })
      setCompareResult(result)
    } catch (err) {
      setError(err instanceof Error ? err.message : '对比失败')
    } finally {
      setComparing(false)
    }
  }

  const handleGenerateWiki = async () => {
    if (!currentRepoId) return
    setGeneratingWiki(true)
    setError(null)
    try {
      const { data } = await generateKnowledgeWiki({
        path: { repoId: currentRepoId },
        query: { language: 'zh' },
      })
      setWiki({
        status: data.status,
        provider: 'codewiki',
        language: data.language,
        pages: [],
      })
      setSelectedWikiPageId('')
      for (let attempt = 0; attempt < 400; attempt += 1) {
        await sleep(3000)
        const { data: current } = await fetchKnowledgeWiki({
          path: { repoId: currentRepoId },
          query: { language: 'zh' },
        })
        setWiki(current)
        if (current.pages.length > 0 || current.status === 'ready') {
          setSelectedWikiPageId(current.pages[0]?.id ?? '')
          return
        }
        if (current.status === 'failed') {
          const msg = (current as Record<string, unknown>).error as string
          throw new Error(msg || 'Wiki 生成失败，请检查 CodeWiki LLM 配置和服务日志')
        }
      }
      throw new Error('Wiki 仍在生成，可稍后重新打开页面查看')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Wiki 生成失败')
    } finally {
      setGeneratingWiki(false)
    }
  }

  const handleGenerateFaq = async () => {
    if (!currentRepoId) return
    setGeneratingFaq(true)
    setError(null)
    try {
      const { data } = await generateRepoFaq({
        path: { repoId: currentRepoId },
        body: { maxItems: 12 },
      })
      setFaq(data)
      setSelectedFaqId(data.items[0]?.id ?? '')
      message.success(data.message || `已生成 ${data.itemCount} 条 FAQ`)
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'FAQ 生成失败'
      setError(msg)
      message.error(msg)
    } finally {
      setGeneratingFaq(false)
    }
  }

  const handleExportFaq = async (format: 'markdown' | 'json') => {
    if (!currentRepoId) return
    try {
      const { data } = await exportRepoFaq({
        path: { repoId: currentRepoId },
        query: { format },
      })
      const blob = new Blob([data.content], {
        type: format === 'markdown' ? 'text/markdown' : 'application/json',
      })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `faq-${currentRepoId}.${format === 'markdown' ? 'md' : 'json'}`
      a.click()
      URL.revokeObjectURL(url)
      message.success(`已导出 ${data.itemCount} 条 FAQ`)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '导出失败')
    }
  }

  const langEntries = Object.entries(overview?.languages || {})
  const langTotal = langEntries.reduce((s, [, c]) => s + c, 0) || 1
  const commits = overview?.commits || []
  const effectiveGraphStatus = graphStatus ?? overview?.graphStatus ?? null
  const selectedWikiPage = wiki?.pages.find((page) => page.id === selectedWikiPageId) ?? wiki?.pages[0]
  const selectedFaq: FaqItem | undefined =
    faq?.items.find((item) => item.id === selectedFaqId) ?? faq?.items[0]
  const selectedTask = buildTasks.find((task) => task.taskId === selectedTaskId) ?? buildTasks[0]

  return (
    <PageShell
      title="知识库"
      description="CodeWiki GraphRAG 代码知识图谱、按需 Wiki 与历史版本对比"
      actions={
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <select
            className="gh-btn"
            value={currentRepoId}
            onChange={(e) => setCurrentRepo(e.target.value)}
            style={{ minWidth: 180 }}
          >
            {repoList.map((r) => (
              <option key={r.id} value={r.id}>
                {r.fullName}
              </option>
            ))}
          </select>
          <button type="button" className="gh-btn gh-btn-primary" disabled={building} onClick={handleBuild}>
            <SyncIcon size={14} />
            {building
              ? buildProgress > 0
                ? `构建中 ${buildProgress}%`
                : '构建中…'
              : '构建知识库'}
          </button>
          <button type="button" className="gh-btn" disabled={building} onClick={handleReset}>
            重置知识库
          </button>
        </div>
      }
    >
      {building && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            <span>
              <strong>{buildProgress > 0 ? `${buildProgress.toFixed(1)}%` : '启动中'}</strong>
              {' · '}
              {buildMessage || '知识库正在后台构建'}
            </span>
          }
          description={
            <div>
              <div className="rp-progress" style={{ marginBottom: 12 }}>
                <div
                  className={`rp-progress-bar${buildProgress <= 0 ? ' indeterminate' : ''}`}
                  style={buildProgress > 0 ? { width: `${Math.min(buildProgress, 100)}%` } : undefined}
                />
              </div>
              <div className="rp-build-phases">
                {BUILD_PHASES.filter((p) => {
                  // 全量构建不显示 update 阶段，增量构建不显示 register/analyze/graphrag
                  if (['register', 'analyze', 'graphrag'].includes(p.key) && buildStage === 'update') return false
                  if (p.key === 'update' && buildStage && !['update'].includes(buildStage) && buildProgress > 0 && buildStage !== '') return false
                  return true
                }).map((phase) => {
                  const status = getPhaseStatus(phase.key, buildStage, buildProgress)
                  return (
                    <div key={phase.key} className={`rp-build-phase ${status}`}>
                      <span className="rp-build-phase-dot" />
                      <span className="rp-build-phase-label">{phase.label}</span>
                    </div>
                  )
                })}
              </div>
            </div>
          }
        />
      )}

      {generatingWiki && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          className="rp-loading-pulse"
          message="Wiki 正在生成"
          description={
            <div className="rp-progress">
              <div className="rp-progress-bar indeterminate" />
            </div>
          }
        />
      )}

      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

      <PortfolioPanel />

      {overview?.status === 'not_indexed' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="尚未构建知识库"
          description="点击「构建知识库」生成代码索引和 GraphRAG 数据。"
        />
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      ) : (
        <>
          <div className="gh-box" style={{ marginBottom: 16 }}>
            <div className="gh-box-header">
              CodeWiki GraphRAG
              <span className="gh-label">{effectiveGraphStatus?.status ?? '未就绪'}</span>
            </div>
            <div className="gh-box-body">
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 12 }}>
                {[
                  ['节点', effectiveGraphStatus?.nodeCount ?? 0],
                  ['边', effectiveGraphStatus?.edgeCount ?? 0],
                  ['社区', effectiveGraphStatus?.communityCount ?? 0],
                  ['片段', effectiveGraphStatus?.chunkCount ?? overview?.chunkCount ?? 0],
                ].map(([label, value]) => (
                  <div key={label} style={{ padding: 12, border: '1px solid var(--border)', borderRadius: 6 }}>
                    <div className="gh-muted" style={{ fontSize: 12 }}>{label}</div>
                    <strong style={{ fontSize: 20 }}>{value}</strong>
                  </div>
                ))}
              </div>
              {effectiveGraphStatus?.message && (
                <p className="gh-muted" style={{ margin: '12px 0 0' }}>{effectiveGraphStatus.message}</p>
              )}
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 12 }}>
                <span className="gh-label">状态 {overview?.status ?? '—'}</span>
                <span className="gh-label">Wiki {overview?.wikiStatus ?? wiki?.status ?? '—'}</span>
                {overview?.indexedAt && <span className="gh-label">索引于 {overview.indexedAt}</span>}
                {overview?.license && <span className="gh-label">License {overview.license}</span>}
                {(overview?.topics || []).map((topic) => (
                  <span key={topic} className="gh-label gh-label-blue">{topic}</span>
                ))}
              </div>
            </div>
          </div>

          <div className="gh-grid-2" style={{ marginBottom: 16 }}>
            <div className="gh-box">
              <div className="gh-box-header">索引质量</div>
              <div className="gh-box-body">
                {overview?.quality ? (
                  <>
                    <div className="gh-data-row">
                      <span className="gh-muted">状态</span>
                      <span className="gh-label">{overview.quality.status}</span>
                    </div>
                    <div className="gh-data-row">
                      <span className="gh-muted">得分</span>
                      <strong>{Math.round(overview.quality.score)}</strong>
                    </div>
                    <div className="gh-data-row">
                      <span className="gh-muted">最近任务</span>
                      <span className="gh-muted" style={{ fontFamily: 'monospace', fontSize: 12 }}>
                        {overview.quality.lastTaskId || '—'}
                      </span>
                    </div>
                  </>
                ) : (
                  <p className="gh-muted" style={{ margin: 0 }}>构建后显示质量报告</p>
                )}
              </div>
            </div>
            <div className="gh-box">
              <div className="gh-box-header">存储与去重</div>
              <div className="gh-box-body">
                {overview?.deduplication ? (
                  <>
                    <div className="gh-data-row">
                      <span className="gh-muted">索引提交</span>
                      <span>{overview.deduplication.indexedCommits}</span>
                    </div>
                    <div className="gh-data-row">
                      <span className="gh-muted">文件引用</span>
                      <span>{overview.deduplication.fileReferences}</span>
                    </div>
                    <div className="gh-data-row">
                      <span className="gh-muted">唯一 chunk</span>
                      <span>{overview.deduplication.uniqueChunkBlobs}</span>
                    </div>
                    {overview.storageModel?.dedupStrategy && (
                      <p className="gh-muted" style={{ margin: '8px 0 0', fontSize: 12 }}>
                        策略：{overview.storageModel.dedupStrategy}
                      </p>
                    )}
                  </>
                ) : (
                  <p className="gh-muted" style={{ margin: 0 }}>构建后显示</p>
                )}
              </div>
            </div>
          </div>

          {(overview?.modules?.length ?? 0) > 0 && (
            <div className="gh-box" style={{ marginBottom: 16 }}>
              <div className="gh-box-header">
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <PackageIcon size={16} />
                  模块分布
                </span>
              </div>
              <div className="gh-box-body" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 8 }}>
                {overview!.modules!.map((mod) => (
                  <div key={mod.name} style={{ padding: 10, border: '1px solid var(--border)', borderRadius: 6 }}>
                    <strong>{mod.name}</strong>
                    <div className="gh-muted" style={{ fontSize: 12, marginTop: 4 }}>{mod.desc}</div>
                    <div className="gh-muted" style={{ fontSize: 12 }}>{mod.files} 文件</div>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="gh-box" style={{ marginBottom: 16 }}>
            <div className="gh-box-header">
              <span>项目 Wiki</span>
              <button
                type="button"
                className="gh-btn gh-btn-primary"
                disabled={generatingWiki || building}
                onClick={handleGenerateWiki}
              >
                {generatingWiki ? '生成中…' : wiki?.status === 'ready' ? '重新生成 Wiki' : '生成 Wiki'}
              </button>
            </div>
            <div className="gh-box-body">
              {wiki?.pages.length ? (
                <div style={{ display: 'grid', gridTemplateColumns: '220px minmax(0, 1fr)', gap: 16 }}>
                  <div style={{ borderRight: '1px solid var(--border)', paddingRight: 12 }}>
                    {wiki.pages
                      .slice()
                      .sort((a, b) => a.order - b.order)
                      .map((page) => (
                        <button
                          key={page.id}
                          type="button"
                          className={`gh-commit-item${selectedWikiPage?.id === page.id ? ' active' : ''}`}
                          style={{ width: '100%', textAlign: 'left' }}
                          onClick={() => setSelectedWikiPageId(page.id)}
                        >
                          <strong>{page.title}</strong>
                          {page.path && <div className="gh-muted" style={{ fontSize: 12 }}>{page.path}</div>}
                        </button>
                      ))}
                  </div>
                  <article>
                    <h3 style={{ marginTop: 0 }}>{selectedWikiPage?.title}</h3>
                    <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', lineHeight: 1.7, margin: 0 }}>
                      {selectedWikiPage?.content}
                    </pre>
                  </article>
                </div>
              ) : (
                <p className="gh-muted" style={{ margin: 0 }}>
                  {wiki?.status === 'queued' || wiki?.status === 'generating'
                    ? 'Wiki 正在生成，请稍后重新打开页面查看。'
                    : '尚未生成 Wiki。按需生成后可在此浏览页面列表和内容。'}
                </p>
              )}
            </div>
          </div>

          <div className="gh-box" style={{ marginBottom: 16 }}>
            <div className="gh-box-header">
              <span>FAQ 聚类</span>
              <div style={{ display: 'flex', gap: 8 }}>
                <button
                  type="button"
                  className="gh-btn"
                  disabled={!faq?.items.length}
                  onClick={() => handleExportFaq('markdown')}
                >
                  导出 MD
                </button>
                <button
                  type="button"
                  className="gh-btn"
                  disabled={!faq?.items.length}
                  onClick={() => handleExportFaq('json')}
                >
                  导出 JSON
                </button>
                <button
                  type="button"
                  className="gh-btn gh-btn-primary"
                  disabled={generatingFaq || building}
                  onClick={handleGenerateFaq}
                >
                  {generatingFaq ? '生成中…' : faq?.status === 'ready' ? '重新生成 FAQ' : '生成 FAQ'}
                </button>
              </div>
            </div>
            <div className="gh-box-body">
              {generatingFaq && (
                <div style={{ marginBottom: 12 }}>
                  <div className="rp-loading-pulse">正在从 GraphRAG 证据聚类 FAQ…</div>
                  <div className="rp-progress">
                    <div className="rp-progress-bar indeterminate" />
                  </div>
                </div>
              )}
              {faq?.items.length ? (
                <div style={{ display: 'grid', gridTemplateColumns: '240px minmax(0, 1fr)', gap: 16 }}>
                  <div style={{ borderRight: '1px solid var(--border)', paddingRight: 12 }}>
                    {faq.items.map((item) => (
                      <button
                        key={item.id}
                        type="button"
                        className={`gh-commit-item${selectedFaq?.id === item.id ? ' active' : ''}`}
                        style={{ width: '100%', textAlign: 'left' }}
                        onClick={() => setSelectedFaqId(item.id)}
                      >
                        <span className="gh-label" style={{ marginBottom: 4 }}>
                          {faqCategoryLabels[item.category] || item.category}
                        </span>
                        <div style={{ fontWeight: 500 }}>{item.question}</div>
                      </button>
                    ))}
                  </div>
                  <article>
                    <h3 style={{ marginTop: 0 }}>{selectedFaq?.question}</h3>
                    <p className="gh-muted" style={{ fontSize: 12 }}>
                      置信度 {Math.round((selectedFaq?.confidence ?? 0) * 100)}% · {selectedFaq?.updatedAt}
                    </p>
                    <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', lineHeight: 1.7, margin: 0 }}>
                      {selectedFaq?.answer}
                    </pre>
                    {selectedFaq?.relatedFiles?.length ? (
                      <div style={{ marginTop: 12 }}>
                        <strong style={{ fontSize: 13 }}>相关文件</strong>
                        {selectedFaq.relatedFiles.map((file) => (
                          <div key={`${file.file}-${file.line}`} className="gh-muted" style={{ fontSize: 12 }}>
                            {file.file}
                            {file.line ? `:${file.line}` : ''}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </article>
                </div>
              ) : (
                <p className="gh-muted" style={{ margin: 0 }}>
                  {faq?.message || '尚未生成 FAQ。构建知识库后，可按主题聚类生成常见问答。'}
                </p>
              )}
            </div>
          </div>

          <div className="gh-box" style={{ marginBottom: 16 }}>
            <div className="gh-box-header">
              构建日志
              <button type="button" className="gh-btn gh-btn-sm" onClick={() => currentRepoId && loadTasks(currentRepoId)}>
                <SyncIcon size={12} />
                刷新
              </button>
            </div>
            <div className="gh-box-body">
              {buildTasks.length === 0 ? (
                <p className="gh-muted" style={{ margin: 0 }}>暂无构建任务记录</p>
              ) : (
                <div style={{ display: 'grid', gridTemplateColumns: '280px minmax(0, 1fr)', gap: 16 }}>
                  <div style={{ borderRight: '1px solid var(--border)', paddingRight: 12, maxHeight: 280, overflowY: 'auto' }}>
                    {buildTasks.map((task) => (
                      <button
                        key={task.taskId}
                        type="button"
                        className={`gh-commit-item${selectedTask?.taskId === task.taskId ? ' active' : ''}`}
                        style={{ width: '100%', textAlign: 'left' }}
                        onClick={() => setSelectedTaskId(task.taskId)}
                      >
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 4 }}>
                          <span className={`gh-label${task.status === 'failed' ? ' gh-label-red' : task.status === 'completed' ? ' gh-label-green' : ''}`}>
                            {task.status}
                          </span>
                          <span className="gh-muted" style={{ fontSize: 12 }}>{Math.round(task.progress)}%</span>
                        </div>
                        <div style={{ fontSize: 12, fontFamily: 'monospace' }}>{task.taskId.slice(0, 8)}</div>
                        <div className="gh-muted" style={{ fontSize: 12 }}>{task.requestedAt}</div>
                      </button>
                    ))}
                  </div>
                  <div>
                    {selectedTask && (
                      <>
                        <p style={{ margin: '0 0 8px' }}>{selectedTask.message || '（无消息）'}</p>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 }}>
                          <span className="gh-label">模式 {selectedTask.mode}</span>
                          <span className="gh-label">质量 {selectedTask.qualityStatus}</span>
                          <span className="gh-label">片段 {selectedTask.chunksTotal}</span>
                          <span className="gh-label">失败文件 {selectedTask.filesFailed}</span>
                        </div>
                        <div className="rp-progress" style={{ marginBottom: 12 }}>
                          <div className="rp-progress-bar" style={{ width: `${Math.min(selectedTask.progress, 100)}%` }} />
                        </div>
                        <h4 style={{ margin: '0 0 8px', fontSize: 13 }}>错误 / 警告</h4>
                        {taskErrors.length === 0 ? (
                          <p className="gh-muted" style={{ margin: 0 }}>该任务无错误明细</p>
                        ) : (
                          <ul style={{ margin: 0, paddingLeft: 18, maxHeight: 180, overflowY: 'auto' }}>
                            {taskErrors.map((err) => (
                              <li key={err.id} style={{ marginBottom: 8, fontSize: 12 }}>
                                <strong>[{err.stage}]</strong> {err.filePath || '—'} · {err.message}
                                {err.retryable ? '（可重试）' : ''}
                              </li>
                            ))}
                          </ul>
                        )}
                      </>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>

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

          {overview?.moduleSummary && (
            <div className="gh-box" style={{ marginBottom: 16 }}>
              <div className="gh-box-header">
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <PackageIcon size={16} />
                  模块概述 (AI)
                </span>
              </div>
              <div className="gh-box-body">
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 13, lineHeight: 1.8, fontFamily: 'inherit' }}>
                  {overview.moduleSummary}
                </pre>
              </div>
            </div>
          )}

          {overview?.indexedFiles?.some((f) => f.summary) && (
            <div className="gh-box" style={{ marginBottom: 16 }}>
              <div className="gh-box-header">
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <FileIcon size={16} />
                  文件摘要 (AI)
                </span>
                <span className="gh-muted" style={{ fontWeight: 400 }}>
                  {overview.indexedFiles.filter((f) => f.summary).length} 个文件
                </span>
              </div>
              <div className="gh-box-body" style={{ maxHeight: 400, overflowY: 'auto' }}>
                {overview.indexedFiles
                  .filter((f) => f.summary)
                  .map((f) => (
                    <div key={f.path} style={{ marginBottom: 10, paddingBottom: 10, borderBottom: '1px solid var(--border)' }}>
                      <div style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--accent)', marginBottom: 4 }}>
                        {f.path}
                      </div>
                      <div style={{ fontSize: 13, color: 'var(--muted)' }}>{f.summary}</div>
                    </div>
                  ))}
              </div>
            </div>
          )}

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
                          <div className="gh-muted">需要：{(row.needs ?? []).join('、')}</div>
                          <div className="gh-muted">不必：{(row.not_needed ?? []).join('、')}</div>
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
