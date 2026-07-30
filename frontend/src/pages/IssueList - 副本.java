import { Alert, Button, Card, Select, Space, Spin, Switch, Table, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  EyeOutlined,
  MailOutlined,
  CommentOutlined,
  RocketOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import { analyzeIssue, type GithubIssue, type IssueAnalysis } from '../api/generated'
import { authAxios } from '../lib/AuthAxios'

const { Text } = Typography

const issueTypeLabels: Record<string, { label: string; color?: string }> = {
  bug_fix: { label: '缺陷修复', color: 'red' },
  feature_request: { label: '功能改进', color: 'green' },
  usage_question: { label: '使用咨询', color: 'blue' },
  documentation: { label: '文档相关', color: 'cyan' },
  performance: { label: '性能问题', color: 'purple' },
  security: { label: '安全相关', color: 'magenta' },
  configuration: { label: '配置/环境', color: 'geekblue' },
  dependency: { label: '依赖/版本', color: 'lime' },
  ci_build: { label: '构建/CI', color: 'volcano' },
  duplicate: { label: '重复问题' },
  insufficient_info: { label: '信息不足', color: 'orange' },
  other: { label: '其他' },
}

type IssueRow = GithubIssue & {
  garbled?: boolean
  duplicateTitle?: boolean
  qualityFlags?: string[]
}

type AnalysisView = IssueAnalysis & {
  confidenceFormula?: string
  confidenceFactors?: Array<{
    name: string
    label: string
    weight: number
    score: number
    contribution: number
    detail: string
  }>
  typeScores?: Record<string, number>
  replied?: boolean
  repliedAt?: string
  postedToGithub?: boolean
}

const cardStyle = {
  borderRadius: 12,
  boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
  border: '1px solid #e5e7eb',
} as const

function confidenceTip(analysis?: AnalysisView | null) {
  if (!analysis) return '尚未分析'
  const formula = analysis.confidenceFormula || '标签/关键词/正文/知识库加权'
  const factors = analysis.confidenceFactors
  if (!factors?.length) return formula
  return (
    <div style={{ maxWidth: 320 }}>
      <div style={{ marginBottom: 6 }}>{formula}</div>
      {factors.map((f) => (
        <div key={f.name} style={{ fontSize: 12, marginBottom: 4 }}>
          <strong>{f.label}</strong>（权重 {f.weight}）得分 {Math.round(f.score * 100)}% · {f.detail}
        </div>
      ))}
    </div>
  )
}

export default function IssueList() {
  const navigate = useNavigate()
  const { currentRepoId, setCurrentRepo, repoList, currentRepo, isRepoListPending } = useRepoContext()
  const [issueState, setIssueState] = useState<'open' | 'closed' | 'all'>('all')
  const [typeFilter, setTypeFilter] = useState('all')
  const [hideGarbled, setHideGarbled] = useState(true)
  const [hideDuplicateTitles, setHideDuplicateTitles] = useState(true)
  const [issues, setIssues] = useState<IssueRow[]>([])
  const [meta, setMeta] = useState({ openIssuesCount: 0, repoFullName: '', filteredOut: 0, rawTotal: 0 })
  const [analyses, setAnalyses] = useState<Record<string, AnalysisView>>({})
  const [loading, setLoading] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [replyingId, setReplyingId] = useState<string | null>(null)
  const [mailing, setMailing] = useState(false)
  const [replyingAll, setReplyingAll] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadIssues = useCallback(async () => {
    if (!currentRepoId) return
    setLoading(true)
    setError(null)
    try {
      const [{ data }, analysesRes] = await Promise.all([
        authAxios.get(`/api/repos/${currentRepoId}/issues`, {
          params: {
            state: issueState,
            hideGarbled,
            hideDuplicateTitles,
          },
        }),
        authAxios.get(`/api/repos/${currentRepoId}/issue-analyses`).catch(() => ({ data: { items: [] } })),
      ])
      setIssues(data.items || [])
      setMeta({
        openIssuesCount: data.openIssuesCount || 0,
        repoFullName: data.repoFullName || '',
        filteredOut: data.filteredOut || 0,
        rawTotal: data.rawTotal || (data.items || []).length,
      })
      const next: Record<string, AnalysisView> = {}
      for (const item of analysesRes.data.items || []) {
        if (item?.issueId) next[item.issueId] = item as AnalysisView
      }
      setAnalyses(next)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载 Issue 失败')
      setIssues([])
    } finally {
      setLoading(false)
    }
  }, [currentRepoId, issueState, hideGarbled, hideDuplicateTitles])

  useEffect(() => {
    loadIssues()
  }, [loadIssues])

  const filtered = useMemo(() => {
    if (typeFilter === 'all') return issues
    return issues.filter((issue) => analyses[issue.id]?.type === typeFilter)
  }, [issues, analyses, typeFilter])

  const handleAnalyzeOne = async (issue: IssueRow, force = false) => {
    if (!currentRepoId) return
    try {
      const { data: analysis } = await analyzeIssue({
        body: { repoId: currentRepoId, issue, force },
      })
      setAnalyses((prev) => ({ ...prev, [issue.id]: analysis as AnalysisView }))
    } catch (err) {
      setError(err instanceof Error ? err.message : '分析失败')
    }
  }

  const handleAnalyzeAll = async () => {
    if (!currentRepoId || analyzing || filtered.length === 0) return
    setAnalyzing(true)
    setError(null)
    try {
      // force=true：用户主动点「分析」时刷新分类；进入页面不会自动分析
      const results = await Promise.all(
        filtered.map(async (issue) => {
          const { data } = await analyzeIssue({
            body: { repoId: currentRepoId, issue, force: true },
          })
          return data as AnalysisView
        }),
      )
      const next: Record<string, AnalysisView> = { ...analyses }
      results.forEach((item) => {
        next[item.issueId] = item
      })
      setAnalyses(next)
      message.success(`已分析 ${results.length} 条（不会自动发 GitHub 评论/邮件）`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '分析失败')
    } finally {
      setAnalyzing(false)
    }
  }

  const handleReplyOne = async (issue: IssueRow) => {
    if (!currentRepoId) return
    setReplyingId(issue.id)
    try {
      if (!analyses[issue.id]) {
        await handleAnalyzeOne(issue, false)
      }
      const { data } = await authAxios.post('/api/issues/reply', { repoId: currentRepoId, issue })
      if (data?.issueId) {
        setAnalyses((prev) => ({ ...prev, [issue.id]: { ...prev[issue.id], ...data, replied: true } }))
      } else {
        setAnalyses((prev) => ({
          ...prev,
          [issue.id]: { ...prev[issue.id], replied: true, postedToGithub: true },
        }))
      }
      message.success(`已将建议回复发布到 #${issue.number}`)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '发布回复失败')
    } finally {
      setReplyingId(null)
    }
  }

  const handleReplyAll = async () => {
    if (!currentRepoId || replyingAll || filtered.length === 0) return
    setReplyingAll(true)
    try {
      const { data } = await authAxios.post('/api/issues/reply-all', {
        repoId: currentRepoId,
        issues: filtered,
      })
      message.success(data.message || `已统一回复 ${data.posted ?? 0} 条`)
      await loadIssues()
    } catch (err) {
      message.error(err instanceof Error ? err.message : '统一回复失败')
    } finally {
      setReplyingAll(false)
    }
  }

  const handleEmailDigest = async () => {
    if (!currentRepoId || mailing || filtered.length === 0) return
    setMailing(true)
    try {
      const payloadIssues = filtered.slice(0, 20)
      // ensure analyzed
      for (const issue of payloadIssues) {
        if (!analyses[issue.id]) {
          await handleAnalyzeOne(issue, false)
        }
      }
      const { data } = await authAxios.post('/api/issues/notify-replies', {
        repoId: currentRepoId,
        issues: payloadIssues,
      })
      if (data.success) {
        message.success(data.message || `已发送 ${data.count} 条摘要邮件`)
      } else {
        message.warning(data.message || '未发送邮件，请检查通知设置与 SMTP')
      }
    } catch (err) {
      message.error(err instanceof Error ? err.message : '邮件发送失败')
    } finally {
      setMailing(false)
    }
  }

  const columns: ColumnsType<IssueRow> = [
    {
      title: 'Issue',
      render: (_, record) => (
        <div>
          <a
            href="#"
            style={{ fontWeight: 600, color: '#111827' }}
            onClick={(e) => {
              e.preventDefault()
              navigate(`/issues/${currentRepoId}/${record.number}`)
            }}
          >
            #{record.number} {record.title}
          </a>
          <div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {record.author} · {record.createdAt}
            </Text>
            {record.state && (
              <Tag
                color={record.state === 'open' ? 'success' : 'default'}
                style={{ marginLeft: 8, borderRadius: 8, fontSize: 11 }}
              >
                {record.state}
              </Tag>
            )}
            {record.garbled && (
              <Tag color="error" style={{ marginLeft: 6, borderRadius: 8 }}>
                疑似乱码
              </Tag>
            )}
            {record.duplicateTitle && (
              <Tag color="warning" style={{ marginLeft: 6, borderRadius: 8 }}>
                标题重复
              </Tag>
            )}
            {analyses[record.id]?.replied && (
              <Tag color="success" style={{ marginLeft: 6, borderRadius: 8 }}>
                已回复
              </Tag>
            )}
            {analyses[record.id] && !analyses[record.id]?.replied && (
              <Tag style={{ marginLeft: 6, borderRadius: 8 }}>未回复</Tag>
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
        if (!analysis) return <Text type="secondary">未分析</Text>
        const meta = issueTypeLabels[analysis.type] || issueTypeLabels.other
        return (
          <Tag color={meta.color} style={{ borderRadius: 8 }}>
            {analysis.typeLabel || meta.label}
          </Tag>
        )
      },
    },
    {
      title: (
        <Tooltip title="置信度 = 0.35×标签 + 0.30×关键词 + 0.20×正文完整度 + 0.15×知识库证据 − 0.10×类别歧义惩罚">
          置信度
        </Tooltip>
      ),
      width: 100,
      render: (_, record) => {
        const analysis = analyses[record.id]
        if (!analysis) return '—'
        return (
          <Tooltip title={confidenceTip(analysis)}>
            <span style={{ cursor: 'help', borderBottom: '1px dashed #9CA3AF' }}>
              {Math.round(analysis.confidence * 100)}%
            </span>
          </Tooltip>
        )
      },
    },
    {
      title: '操作',
      width: 280,
      render: (_, record) => (
        <Space size={6} wrap>
          <Button
            size="small"
            icon={<RocketOutlined />}
            onClick={() => handleAnalyzeOne(record, Boolean(analyses[record.id]))}
          >
            {analyses[record.id] ? '重分析' : '分析'}
          </Button>
          <Button
            size="small"
            icon={<CommentOutlined />}
            loading={replyingId === record.id}
            onClick={() => handleReplyOne(record)}
          >
            自动回复
          </Button>
          <Button
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/issues/${currentRepoId}/${record.number}`)}
          >
            详情
          </Button>
        </Space>
      ),
    },
  ]

  // 修复描述文字竖排错乱：强制宽度 100% + 正常换行
  const pageDescription = (
    <div style={{
      width: '100%',
      whiteSpace: 'normal',
      wordBreak: 'break-word',
      writingMode: 'horizontal-tb',
      lineHeight: 1.6,
      color: '#6b7280',
      fontSize: 13,
    }}>
      多信号分类 + 可解释置信度；分析与 GitHub 回复/邮件通知分离，需手动触发
    </div>
  )

  return (
    <PageShell
      title="Issue 智能分析"
      description={pageDescription}
      actions={
        <Space wrap size={8}>
          <Select
            value={currentRepoId || undefined}
            onChange={(value) => setCurrentRepo(value)}
            style={{ minWidth: 220 }}
            placeholder="选择仓库"
            disabled={isRepoListPending}
            options={repoList.map((r) => ({ value: r.id, label: r.fullName }))}
          />
          <Select
            value={issueState}
            onChange={(value) => setIssueState(value)}
            style={{ minWidth: 120 }}
            options={[
              { value: 'all', label: '全部状态' },
              { value: 'open', label: 'Open' },
              { value: 'closed', label: 'Closed' },
            ]}
          />
          <Select
            value={typeFilter}
            onChange={setTypeFilter}
            style={{ minWidth: 160 }}
            options={[
              { value: 'all', label: '全部类型' },
              ...Object.entries(issueTypeLabels).map(([value, meta]) => ({
                value,
                label: meta.label,
              })),
            ]}
          />
          <Button icon={<SyncOutlined />} onClick={loadIssues} disabled={loading || !currentRepoId} />
          <Button
            type="primary"
            disabled={analyzing || !currentRepoId || filtered.length === 0}
            loading={analyzing}
            onClick={handleAnalyzeAll}
          >
            分析当前列表
          </Button>
          <Button
            icon={<CommentOutlined />}
            disabled={replyingAll || !currentRepoId || filtered.length === 0}
            loading={replyingAll}
            onClick={handleReplyAll}
          >
            统一回复未回复项
          </Button>
          <Button
            icon={<MailOutlined />}
            disabled={mailing || !currentRepoId || filtered.length === 0}
            loading={mailing}
            onClick={handleEmailDigest}
          >
            邮件提示回复
          </Button>
        </Space>
      }
    >
      <div className="issue-page">
        {isRepoListPending && (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin />
          </div>
        )}
        {!isRepoListPending && !currentRepoId && (
          <Alert type="info" showIcon message="请先在顶栏或此处选择仓库" style={{ borderRadius: 10 }} />
        )}
        {error && <Alert type="error" message={error} showIcon style={{ borderRadius: 10 }} />}
        <Card style={cardStyle} styles={{ body: { padding: '12px 20px' } }}>
          <Space wrap size={16}>
            <Space>
              <Text type="secondary">过滤乱码</Text>
              <Switch checked={hideGarbled} onChange={setHideGarbled} />
            </Space>
            <Space>
              <Text type="secondary">过滤标题重复</Text>
              <Switch checked={hideDuplicateTitles} onChange={setHideDuplicateTitles} />
            </Space>
            <Text type="secondary">
              本页 {meta.rawTotal} 条 → 展示 {issues.length} 条
              {meta.filteredOut > 0 ? `（已隐藏 ${meta.filteredOut}）` : ''}
            </Text>
          </Space>
        </Card>
        {currentRepoId && !loading && issues.length === 0 && !error && (
          <Alert
            type="warning"
            showIcon
            style={{ borderRadius: 10 }}
            message="当前筛选下没有 Issue"
            description={
              <>
                仓库 {meta.repoFullName || currentRepo?.fullName} 约有{' '}
                <strong>{meta.openIssuesCount ?? currentRepo?.openIssues ?? 0}</strong> 个 Open Issue。
                可关闭「过滤乱码/标题重复」或切换状态后再刷新。
              </>
            }
          />
        )}
        <Card
          style={cardStyle}
          title={
            <span style={{ fontWeight: 700, fontSize: 15 }}>
              共 {filtered.length} 条 Issue
              {typeFilter !== 'all' && (
                <Text type="secondary" style={{ fontWeight: 400, fontSize: 12, marginLeft: 8 }}>
                  （类型筛选仅显示已分析项）
                </Text>
              )}
            </span>
          }
          styles={{ body: { paddingTop: 8 } }}
        >
          <Table
            rowKey="id"
            columns={columns}
            dataSource={filtered}
            loading={loading}
            pagination={{ pageSize: 10 }}
            locale={{ emptyText: currentRepoId ? '暂无 Issue 数据' : '请选择仓库' }}
          />
        </Card>
      </div>
    </PageShell>
  )
}