import { Alert, Spin, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { EyeIcon, SyncIcon, RocketIcon } from '@primer/octicons-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import {
  analyzeIssue,
  fetchRepositoryIssues,
  type GithubIssue,
  type IssueAnalysis,
} from '../lib/api'

const issueTypeLabels = {
  usage_question: { label: '使用问题', className: 'gh-label gh-label-blue' },
  duplicate: { label: '重复问题', className: 'gh-label' },
  insufficient_info: { label: '信息不足', className: 'gh-label gh-label-orange' },
  bug_fix: { label: '缺陷修复', className: 'gh-label gh-label-red' },
  feature_request: { label: '功能改进', className: 'gh-label gh-label-green' },
  other: { label: '其他', className: 'gh-label' },
}

export default function IssueList() {
  const navigate = useNavigate()
  const { repoId, setRepoId, repos, loading: reposLoading } = useRepoContext()
  const [issueState, setIssueState] = useState<'open' | 'closed' | 'all'>('all')
  const [typeFilter, setTypeFilter] = useState('all')
  const [issues, setIssues] = useState<GithubIssue[]>([])
  const [meta, setMeta] = useState({ openIssuesCount: 0, repoFullName: '' })
  const [analyses, setAnalyses] = useState<Record<string, IssueAnalysis>>({})
  const [loading, setLoading] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadIssues = useCallback(async () => {
    if (!repoId) return
    setLoading(true)
    setError(null)
    try {
      const data = await fetchRepositoryIssues(repoId, { state: issueState, perPage: 30 })
      setIssues(data.items)
      setMeta({ openIssuesCount: data.openIssuesCount, repoFullName: data.repoFullName })
      setAnalyses({})
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载 Issue 失败')
      setIssues([])
    } finally {
      setLoading(false)
    }
  }, [repoId, issueState])

  useEffect(() => {
    loadIssues()
  }, [loadIssues])

  const filtered = useMemo(() => {
    if (typeFilter === 'all') return issues
    return issues.filter((issue) => analyses[issue.id]?.type === typeFilter)
  }, [issues, analyses, typeFilter])

  const handleAnalyzeOne = async (issue: GithubIssue) => {
    if (!repoId) return
    try {
      const analysis = await analyzeIssue(repoId, issue)
      setAnalyses((prev) => ({ ...prev, [issue.id]: analysis }))
    } catch (err) {
      setError(err instanceof Error ? err.message : '分析失败')
    }
  }

  const handleAnalyzeAll = async () => {
    if (!repoId || analyzing || issues.length === 0) return
    setAnalyzing(true)
    setError(null)
    try {
      const results = await Promise.all(issues.map((issue) => analyzeIssue(repoId, issue)))
      const next: Record<string, IssueAnalysis> = {}
      results.forEach((item) => {
        next[item.issueId] = item
      })
      setAnalyses(next)
    } catch (err) {
      setError(err instanceof Error ? err.message : '分析失败')
    } finally {
      setAnalyzing(false)
    }
  }

  const columns: ColumnsType<GithubIssue> = [
    {
      title: 'Issue',
      render: (_, record) => (
        <div>
          <a
            className="gh-link"
            href="#"
            onClick={(e) => {
              e.preventDefault()
              navigate(`/issues/${repoId}/${record.number}`)
            }}
          >
            #{record.number} {record.title}
          </a>
          <div className="gh-muted" style={{ fontSize: 12 }}>
            {record.author} · {record.createdAt}
            {record.state && (
              <span className={`gh-label${record.state === 'open' ? ' gh-label-green' : ''}`} style={{ marginLeft: 6 }}>
                {record.state}
              </span>
            )}
          </div>
        </div>
      ),
    },
    {
      title: 'AI 分类',
      width: 120,
      render: (_, record) => {
        const analysis = analyses[record.id]
        if (!analysis) return <span className="gh-label">未分析</span>
        const meta = issueTypeLabels[analysis.type]
        return <span className={meta.className}>{meta.label}</span>
      },
    },
    {
      title: '置信度',
      width: 90,
      render: (_, record) => {
        const analysis = analyses[record.id]
        return analysis ? `${Math.round(analysis.confidence * 100)}%` : '—'
      },
    },
    {
      title: '操作',
      width: 180,
      render: (_, record) => (
        <div style={{ display: 'flex', gap: 6 }}>
          <button type="button" className="gh-btn gh-btn-sm" onClick={() => handleAnalyzeOne(record)}>
            <RocketIcon size={12} />
            分析
          </button>
          <button
            type="button"
            className="gh-btn gh-btn-sm"
            onClick={() => navigate(`/issues/${repoId}/${record.number}`)}
          >
            <EyeIcon size={12} />
            详情
          </button>
        </div>
      ),
    },
  ]

  const currentRepo = repos.find((r) => r.id === repoId)

  return (
    <PageShell
      title="Issue 智能分析"
      description="从 GitHub 拉取 Issue 并生成类型判断与回复建议（配置 LLM 后使用 OpenRouter 增强）"
      actions={
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <select
            className="gh-btn"
            value={repoId}
            onChange={(e) => setRepoId(e.target.value)}
            style={{ minWidth: 200 }}
            disabled={reposLoading}
          >
            {!repoId && <option value="">选择仓库</option>}
            {repos.map((r) => (
              <option key={r.id} value={r.id}>
                {r.fullName}
              </option>
            ))}
          </select>
          <select className="gh-btn" value={issueState} onChange={(e) => setIssueState(e.target.value as typeof issueState)}>
            <option value="all">全部状态</option>
            <option value="open">Open</option>
            <option value="closed">Closed</option>
          </select>
          <select className="gh-btn" value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
            <option value="all">全部类型（含未分析）</option>
            {Object.entries(issueTypeLabels).map(([value, meta]) => (
              <option key={value} value={value}>
                已分析：{meta.label}
              </option>
            ))}
          </select>
          <button type="button" className="gh-btn" onClick={loadIssues} disabled={loading || !repoId}>
            <SyncIcon size={12} />
          </button>
          <button
            type="button"
            className="gh-btn gh-btn-primary"
            disabled={analyzing || !repoId || issues.length === 0}
            onClick={handleAnalyzeAll}
          >
            {analyzing ? '分析中…' : '分析当前列表'}
          </button>
        </div>
      }
    >
      {reposLoading && (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin />
        </div>
      )}

      {!reposLoading && !repoId && (
        <Alert type="info" showIcon message="请先在顶栏或此处选择仓库" style={{ marginBottom: 16 }} />
      )}

      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

      {repoId && !loading && issues.length === 0 && !error && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="当前筛选下没有 Issue"
          description={
            <>
              仓库 {meta.repoFullName || currentRepo?.fullName} 在 GitHub 上约有{' '}
              <strong>{meta.openIssuesCount ?? currentRepo?.openIssues ?? 0}</strong> 个 Open Issue。
              若仍为 0，可能是私有仓库权限、该仓库确实无 Issue，或请切换「全部状态」后刷新。
            </>
          }
        />
      )}

      <div className="gh-box">
        <div className="gh-box-header">
          共 {filtered.length} 条 Issue
          {typeFilter !== 'all' && (
            <span className="gh-muted" style={{ fontWeight: 400, fontSize: 12 }}>
              （类型筛选仅显示已分析项）
            </span>
          )}
        </div>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: repoId ? '暂无 Issue 数据' : '请选择仓库' }}
        />
      </div>
    </PageShell>
  )
}
