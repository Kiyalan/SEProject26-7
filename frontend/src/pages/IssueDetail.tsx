import { Alert, Spin } from 'antd'
import { ArrowLeftIcon, CopyIcon, MarkGithubIcon } from '@primer/octicons-react'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import {
  analyzeIssue,
  fetchRepository,
  fetchRepositoryIssue,
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

export default function IssueDetail() {
  const { repoId, issueNumber } = useParams()
  const navigate = useNavigate()
  const { setRepoId } = useRepoContext()
  const [issue, setIssue] = useState<GithubIssue | null>(null)
  const [repoName, setRepoName] = useState('')
  const [analysis, setAnalysis] = useState<IssueAnalysis | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (repoId) setRepoId(repoId)
  }, [repoId, setRepoId])

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
        const [repo, issueData] = await Promise.all([
          fetchRepository(repoId!),
          fetchRepositoryIssue(repoId!, num),
        ])
        setRepoName(repo.fullName)
        setIssue(issueData)
        setAnalysis(await analyzeIssue(repoId!, issueData))
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载失败')
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [repoId, issueNumber])

  if (loading) {
    return (
      <div className="gh-main" style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    )
  }

  if (error || !issue || !analysis) {
    return (
      <div className="gh-main">
        <Alert type="error" message={error || 'Issue 不存在'} showIcon />
      </div>
    )
  }

  const typeMeta = issueTypeLabels[analysis.type]

  return (
    <PageShell title={`#${issue.number} ${issue.title}`} description={repoName}>
      <button type="button" className="gh-btn gh-btn-sm" style={{ marginBottom: 16 }} onClick={() => navigate('/issues')}>
        <ArrowLeftIcon size={14} />
        返回列表
      </button>

      {analysis.llmEnhanced && (
        <Alert type="success" showIcon message="已由 LLM（OpenRouter）增强分析" style={{ marginBottom: 16 }} />
      )}

      <div className="gh-grid-2">
        <div className="gh-box">
          <div className="gh-box-header">Issue 内容</div>
          <div className="gh-box-body">
            <div style={{ marginBottom: 12, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              <span className="gh-label">{repoName}</span>
              {issue.state && <span className="gh-label gh-label-green">{issue.state}</span>}
              {issue.labels.map((label) => (
                <span key={label} className="gh-label">
                  {label}
                </span>
              ))}
            </div>
            <p style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{issue.body || '（无正文）'}</p>
            <p className="gh-muted" style={{ margin: '12px 0 0', fontSize: 12 }}>
              {issue.author} · {issue.createdAt}
            </p>
          </div>
        </div>

        <div>
          <div className="gh-box">
            <div className="gh-box-header">AI 分析</div>
            <div className="gh-box-body">
              <div className="gh-data-row">
                <span className="gh-muted">分类</span>
                <span className={typeMeta.className}>{analysis.typeLabel}</span>
              </div>
              <div className="gh-data-row">
                <span className="gh-muted">置信度</span>
                <span>{Math.round(analysis.confidence * 100)}%</span>
              </div>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>分析摘要</h4>
              <p style={{ margin: 0 }}>{analysis.summary}</p>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>分类依据</h4>
              <p className="gh-muted" style={{ margin: 0 }}>{analysis.reason}</p>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>相关文件</h4>
              {analysis.relatedFiles.length ? (
                analysis.relatedFiles.map((file) => (
                  <div key={`${file.file}-${file.line}`} className="gh-muted" style={{ fontSize: 13 }}>
                    {file.file}
                    {file.line ? `:${file.line}` : ''}
                  </div>
                ))
              ) : (
                <p className="gh-muted" style={{ margin: 0 }}>
                  未检索到相关文件（请先在知识库构建索引）
                </p>
              )}
            </div>
          </div>

          <div className="gh-box">
            <div className="gh-box-header">建议回复</div>
            <div className="gh-box-body">
              <p style={{ whiteSpace: 'pre-wrap' }}>{analysis.suggestedReply}</p>
              <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                <button
                  type="button"
                  className="gh-btn"
                  onClick={() => navigator.clipboard.writeText(analysis.suggestedReply)}
                >
                  <CopyIcon size={14} />
                  复制
                </button>
                <a className="gh-btn gh-btn-primary" href={issue.htmlUrl} target="_blank" rel="noreferrer">
                  <MarkGithubIcon size={14} />
                  GitHub
                </a>
              </div>
            </div>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
