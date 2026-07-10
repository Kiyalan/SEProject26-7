import { Alert, Spin } from 'antd'
import {
  ArrowLeftIcon,
  BookIcon,
  CommentDiscussionIcon,
  IssueOpenedIcon,
  MarkGithubIcon,
} from '@primer/octicons-react'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import { fetchRepository, fetchRepositoryIssues } from '../lib/api'
import type { GithubIssue } from '../lib/BackendTypes'
import type { Repository } from '../lib/FrontendTypes'

export default function RepoDetail() {
  const { repoId } = useParams()
  const navigate = useNavigate()
  const { setRepoId } = useRepoContext()
  const [repo, setRepo] = useState<Repository | null>(null)
  const [issues, setIssues] = useState<GithubIssue[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (repoId) setRepoId(repoId)
  }, [repoId, setRepoId])

  useEffect(() => {
    if (!repoId) return

    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [repoData, issueData] = await Promise.all([
          fetchRepository(repoId!),
          fetchRepositoryIssues(repoId!),
        ])
        setRepo(repoData)
        setIssues(issueData.items)
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载失败')
      } finally {
        setLoading(false)
      }
    }

    load()
  }, [repoId])

  if (loading) {
    return (
      <div className="gh-main" style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    )
  }

  if (error || !repo) {
    return (
      <div className="gh-main">
        <Alert type="error" message={error || '仓库不存在'} showIcon />
      </div>
    )
  }

  return (
    <PageShell
      title={repo.fullName}
      description={repo.description || '暂无描述'}
      actions={
        <div style={{ display: 'flex', gap: 8 }}>
          {repo.htmlUrl && (
            <a className="gh-btn" href={repo.htmlUrl} target="_blank" rel="noreferrer">
              <MarkGithubIcon size={14} />
              GitHub
            </a>
          )}
          <button type="button" className="gh-btn" onClick={() => navigate('/knowledge')}>
            <BookIcon size={14} />
            知识库
          </button>
          <button type="button" className="gh-btn gh-btn-primary" onClick={() => navigate('/chat')}>
            <CommentDiscussionIcon size={14} />
            问答
          </button>
        </div>
      }
    >
      <button
        type="button"
        className="gh-btn gh-btn-sm"
        style={{ marginBottom: 16 }}
        onClick={() => navigate('/repos')}
      >
        <ArrowLeftIcon size={14} />
        返回列表
      </button>

      <div className="gh-grid-3" style={{ marginBottom: 16 }}>
        <div className="gh-box gh-stat">
          <div className="gh-stat-value">{repo.stars.toLocaleString()}</div>
          <div className="gh-stat-label">Stars</div>
        </div>
        <div className="gh-box gh-stat">
          <div className="gh-stat-value">{repo.openIssues}</div>
          <div className="gh-stat-label">Open Issues</div>
        </div>
        <div className="gh-box gh-stat">
          <div className="gh-stat-value">{issues.length}</div>
          <div className="gh-stat-label">最近 Issue</div>
        </div>
      </div>

      <div className="gh-grid-2">
        <div className="gh-box">
          <div className="gh-box-header">About</div>
          <div className="gh-box-body">
            <div className="gh-data-row">
              <span className="gh-muted">主要语言</span>
              <span>{repo.language}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">默认分支</span>
              <span>{repo.defaultBranch || 'main'}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">可见性</span>
              <span>{repo.private ? 'Private' : 'Public'}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">同步时间</span>
              <span>{repo.lastSync}</span>
            </div>
          </div>
        </div>

        <div className="gh-box">
          <div className="gh-box-header">
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <IssueOpenedIcon size={16} />
              最近 Issue
            </span>
            <button type="button" className="gh-btn gh-btn-sm" onClick={() => navigate('/issues')}>
              全部分析
            </button>
          </div>
          <div className="gh-box-body" style={{ padding: 0 }}>
            {issues.length === 0 ? (
              <p className="gh-muted" style={{ padding: 16, margin: 0 }}>暂无开放的 Issue</p>
            ) : (
              issues.map((issue) => (
                <div
                  key={issue.id}
                  style={{ padding: '12px 16px', borderBottom: '1px solid var(--gh-border-muted)' }}
                >
                  <a className="gh-link" href={issue.htmlUrl} target="_blank" rel="noreferrer">
                    #{issue.number} {issue.title}
                  </a>
                  <div className="gh-muted" style={{ fontSize: 12, marginTop: 4 }}>
                    {issue.author} · {issue.createdAt}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </PageShell>
  )
}
