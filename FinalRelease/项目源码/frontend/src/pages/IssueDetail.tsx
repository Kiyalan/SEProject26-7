import { Alert, Button, Card, Space, Spin, Tag, Typography, message } from 'antd'
import {
  ArrowLeftOutlined,
  CommentOutlined,
  CopyOutlined,
  GithubOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import {
  analyzeIssue,
  fetchRepositoryIssue,
  type GithubIssue,
  type IssueAnalysis,
} from '../api/generated'
import { authAxios } from '../lib/AuthAxios'

const { Text, Paragraph } = Typography

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
  postedToGithub?: boolean
  replied?: boolean
  repliedAt?: string
}

const cardStyle = {
  borderRadius: 12,
  boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
  border: '1px solid #e5e7eb',
} as const

export default function IssueDetail() {
  const { repoId, issueNumber } = useParams()
  const navigate = useNavigate()
  const { repoList, setCurrentRepo, syncRepo } = useRepoContext()
  const [issue, setIssue] = useState<GithubIssue | null>(null)
  const [repoName, setRepoName] = useState('')
  const [analysis, setAnalysis] = useState<AnalysisView | null>(null)
  const [loading, setLoading] = useState(true)
  const [reanalyzing, setReanalyzing] = useState(false)
  const [replying, setReplying] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (repoId) setCurrentRepo(repoId)
  }, [repoId, setCurrentRepo])

  useEffect(() => {
    if (!repoId || !issueNumber) return
    const num = Number(issueNumber)
    if (!Number.isFinite(num)) {
      setError('Issue 编号无效')
      setLoading(false)
      return
    }

    async function load() {
      setLoading(true)
      setError(null)
      try {
        const cachedRepo = repoList.find((r) => r.id === repoId)
        const repoNamePromise = cachedRepo
          ? Promise.resolve(cachedRepo.fullName)
          : syncRepo(repoId!).then((r) => r.fullName)

        const [name, issueRes] = await Promise.all([
          repoNamePromise,
          fetchRepositoryIssue({ path: { repoId: repoId!, issueNumber: num } }),
        ])
        setRepoName(name)
        setIssue(issueRes.data)
        const { data: analysisData } = await analyzeIssue({
          body: { repoId: repoId!, issue: issueRes.data },
        })
        setAnalysis(analysisData as AnalysisView)
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载失败')
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [repoId, issueNumber, repoList, syncRepo])

  const handleReanalyze = async () => {
    if (!repoId || !issue || reanalyzing) return
    setReanalyzing(true)
    setError(null)
    try {
      const { data } = await analyzeIssue({
        body: { repoId, issue, force: true },
      })
      setAnalysis(data as AnalysisView)
      message.success('已重新分析（未自动发评论）')
    } catch (err) {
      const msg = err instanceof Error ? err.message : '重新分析失败'
      setError(msg)
      message.error(msg)
    } finally {
      setReanalyzing(false)
    }
  }

  const handleCopy = async () => {
    if (!analysis) return
    try {
      await navigator.clipboard.writeText(analysis.suggestedReply)
      message.success('建议回复已复制')
    } catch {
      message.error('复制失败，请手动选择文本')
    }
  }

  const handlePostReply = async () => {
    if (!repoId || !issue || replying) return
    setReplying(true)
    try {
      const { data } = await authAxios.post('/api/issues/reply', { repoId, issue })
      setAnalysis(data as AnalysisView)
      message.success('已将建议回复发布到 GitHub Issue 评论')
    } catch (err) {
      message.error(err instanceof Error ? err.message : '发布失败')
    } finally {
      setReplying(false)
    }
  }

  if (loading) {
    return (
      <div className="gh-main" style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
        <p className="gh-muted" style={{ marginTop: 12 }}>
          正在加载 Issue…
        </p>
      </div>
    )
  }

  if (error && (!issue || !analysis)) {
    return (
      <div className="gh-main">
        <Alert type="error" message={error} showIcon />
      </div>
    )
  }

  if (!issue || !analysis) {
    return (
      <div className="gh-main">
        <Alert type="error" message="Issue 不存在" showIcon />
      </div>
    )
  }

  const typeMeta = issueTypeLabels[analysis.type] || issueTypeLabels.other

  return (
    <PageShell title={`#${issue.number} ${issue.title}`} description={repoName}>
      <div className="issue-page">
        <Space wrap style={{ marginBottom: 8 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/issues')}>
            返回列表
          </Button>
          <Button icon={<SyncOutlined />} loading={reanalyzing} onClick={handleReanalyze}>
            强制重新分析
          </Button>
        </Space>

        {error && <Alert type="error" message={error} showIcon />}

        {analysis.llmEnhanced && (
          <Alert type="success" showIcon message="建议回复已由 LLM（OpenRouter）增强润色" />
        )}

        <div className="gh-grid-2">
          <Card style={cardStyle} title={<span style={{ fontWeight: 700 }}>Issue 内容</span>}>
            <Space wrap style={{ marginBottom: 12 }}>
              <Tag>{repoName}</Tag>
              {issue.state && <Tag color="success">{issue.state}</Tag>}
              {issue.labels.map((label) => (
                <Tag key={label}>{label}</Tag>
              ))}
            </Space>
            <Paragraph style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
              {issue.body || '（无正文）'}
            </Paragraph>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {issue.author} · {issue.createdAt}
            </Text>
          </Card>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            <Card style={cardStyle} title={<span style={{ fontWeight: 700 }}>AI 分析</span>}>
              <div className="gh-data-row">
                <Text type="secondary">分类</Text>
                <Tag color={typeMeta.color}>{analysis.typeLabel || typeMeta.label}</Tag>
              </div>
              <div className="gh-data-row">
                <Text type="secondary">置信度</Text>
                <Text strong>{Math.round(analysis.confidence * 100)}%</Text>
              </div>
              <div className="gh-data-row">
                <Text type="secondary">分析时间</Text>
                <span>{analysis.analyzedAt}</span>
              </div>

              <Alert
                type="info"
                showIcon
                style={{ marginTop: 12, marginBottom: 12, borderRadius: 8 }}
                message="置信度怎么算"
                description={
                  <div>
                    <div style={{ marginBottom: 8 }}>
                      {analysis.confidenceFormula ||
                        '0.35×标签匹配 + 0.30×关键词匹配 + 0.20×正文完整度 + 0.15×知识库证据 − 0.10×类别歧义惩罚'}
                    </div>
                    {analysis.confidenceFactors?.length ? (
                      <ul style={{ margin: 0, paddingLeft: 18 }}>
                        {analysis.confidenceFactors.map((f) => (
                          <li key={f.name} style={{ marginBottom: 4 }}>
                            <strong>{f.label}</strong>（权重 {f.weight}）得分 {Math.round(f.score * 100)}%
                            ，贡献 {f.contribution.toFixed(2)} — {f.detail}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <Text type="secondary">重新分析后可看到分项明细</Text>
                    )}
                  </div>
                }
              />

              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>分析摘要</h4>
              <p style={{ margin: 0 }}>{analysis.summary}</p>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>分类依据</h4>
              <p className="gh-muted" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                {analysis.reason}
              </p>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>相关文件</h4>
              {analysis.relatedFiles.length ? (
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {analysis.relatedFiles.map((file) => (
                    <li
                      key={`${file.file}-${file.line}`}
                      style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 13 }}
                    >
                      {file.file}
                      {file.line ? `:${file.line}` : ''}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="gh-muted" style={{ margin: 0 }}>
                  未检索到相关文件（请先构建知识库，或强制重新分析）
                </p>
              )}
            </Card>

            <Card style={cardStyle} title={<span style={{ fontWeight: 700 }}>建议回复</span>}>
              <Paragraph style={{ whiteSpace: 'pre-wrap' }}>{analysis.suggestedReply}</Paragraph>
              <Space wrap>
                <Button icon={<CopyOutlined />} onClick={handleCopy}>
                  复制
                </Button>
                <Button type="primary" icon={<CommentOutlined />} loading={replying} onClick={handlePostReply}>
                  发布到 GitHub
                </Button>
                <Button icon={<GithubOutlined />} href={issue.htmlUrl} target="_blank" rel="noreferrer">
                  打开 GitHub
                </Button>
              </Space>
              {(analysis.postedToGithub || analysis.replied) && (
                <Alert type="success" showIcon style={{ marginTop: 12 }} message="已回复（已发布到 GitHub 评论）" />
              )}
              {analysis && !analysis.postedToGithub && !analysis.replied && (
                <Alert type="info" showIcon style={{ marginTop: 12 }} message="尚未回复到 GitHub" />
              )}
            </Card>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
