import { Alert, Spin, message } from 'antd'
import { ArrowLeftIcon, CopyIcon, MarkGithubIcon, SyncIcon } from '@primer/octicons-react'
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
  const { repoList, setCurrentRepo, syncRepo } = useRepoContext()
  const [issue, setIssue] = useState<GithubIssue | null>(null)
  const [repoName, setRepoName] = useState('')
  const [analysis, setAnalysis] = useState<IssueAnalysis | null>(null)
  const [loading, setLoading] = useState(true)
  const [reanalyzing, setReanalyzing] = useState(false)
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
        setAnalysis(analysisData)
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
      setAnalysis(data)
      message.success('已重新分析')
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

  if (loading) {
    return (
      <div className="gh-main" style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
        <p className="gh-muted rp-loading-pulse" style={{ marginTop: 12 }}>
          正在加载 Issue 并分析…
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

  const typeMeta = issueTypeLabels[analysis.type]

  return (
    <PageShell title={`#${issue.number} ${issue.title}`} description={repoName}>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        <button type="button" className="gh-btn gh-btn-sm" onClick={() => navigate('/issues')}>
          <ArrowLeftIcon size={14} />
          返回列表
        </button>
        <button
          type="button"
          className="gh-btn gh-btn-sm"
          disabled={reanalyzing}
          onClick={handleReanalyze}
        >
          <SyncIcon size={14} />
          {reanalyzing ? '重新分析中…' : '强制重新分析'}
        </button>
      </div>

      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

      {reanalyzing && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="正在重新检索相关文件并分类…"
        />
      )}

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
              <div className="gh-data-row">
                <span className="gh-muted">分析时间</span>
                <span>{analysis.analyzedAt}</span>
              </div>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>分析摘要</h4>
              <p style={{ margin: 0 }}>{analysis.summary}</p>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>分类依据</h4>
              <p className="gh-muted" style={{ margin: 0 }}>{analysis.reason}</p>
              <h4 style={{ margin: '12px 0 4px', fontSize: 14 }}>相关文件</h4>
              {analysis.relatedFiles.length ? (
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {analysis.relatedFiles.map((file) => (
                    <li key={`${file.file}-${file.line}`} style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 13 }}>
                      {file.file}
                      {file.line ? `:${file.line}` : ''}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="gh-muted" style={{ margin: 0 }}>
                  未检索到相关文件（请先在知识库构建索引，或点击「强制重新分析」）
                </p>
              )}
            </div>
          </div>

          <div className="gh-box">
            <div className="gh-box-header">建议回复</div>
            <div className="gh-box-body">
              <p style={{ whiteSpace: 'pre-wrap' }}>{analysis.suggestedReply}</p>
              <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                <button type="button" className="gh-btn" onClick={handleCopy}>
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
