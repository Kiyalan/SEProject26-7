import { Alert, Spin } from 'antd'
import { SyncIcon, MarkGithubIcon } from '@primer/octicons-react'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PageShell from '../components/layout/PageShell'
import { fetchRepositories } from '../lib/api'
import { getUsername } from '../lib/auth'
import type { Repository } from '../lib/FrontendTypes'

const statusLabel = {
  synced: { text: '已同步', className: 'gh-label gh-label-green' },
  syncing: { text: '同步中', className: 'gh-label gh-label-blue' },
  error: { text: '失败', className: 'gh-label gh-label-red' },
}

export default function RepoList() {
  const navigate = useNavigate()
  const [repos, setRepos] = useState<Repository[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadRepos = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchRepositories()
      setRepos(data.items)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载仓库失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadRepos()
  }, [loadRepos])

  return (
    <PageShell
      title="你的仓库"
      description={`已连接 GitHub 账号 ${getUsername()} · 共 ${repos.length} 个仓库`}
      actions={
        <button type="button" className="gh-btn" onClick={loadRepos} disabled={loading}>
          <SyncIcon size={14} />
          刷新
        </button>
      }
    >
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

      <div className="gh-box">
        <div className="gh-box-header">
          <span>Repositories</span>
          <span className="gh-muted" style={{ fontWeight: 400 }}>{repos.length}</span>
        </div>
        {loading ? (
          <div style={{ textAlign: 'center', padding: 48 }}>
            <Spin />
          </div>
        ) : repos.length === 0 ? (
          <div className="gh-box-body">
            <p className="gh-muted">暂无仓库，请确认 GitHub OAuth 权限包含 repo 读取。</p>
          </div>
        ) : (
          repos.map((repo) => (
            <div key={repo.id} className="gh-repo-list-item">
              <div style={{ flex: 1, minWidth: 0 }}>
                <a
                  className="gh-repo-name"
                  href={`/repos/${repo.id}`}
                  onClick={(e) => {
                    e.preventDefault()
                    navigate(`/repos/${repo.id}`)
                  }}
                >
                  {repo.fullName}
                </a>
                {repo.private && (
                  <span className="gh-label" style={{ marginLeft: 8, fontSize: 11 }}>
                    Private
                  </span>
                )}
                <p className="gh-muted" style={{ margin: '4px 0 8px' }}>
                  {repo.description || '暂无描述'}
                </p>
                <div style={{ display: 'flex', gap: 12, fontSize: 12 }} className="gh-muted">
                  {repo.language && repo.language !== '—' && (
                    <span>
                      <span
                        style={{
                          display: 'inline-block',
                          width: 10,
                          height: 10,
                          borderRadius: '50%',
                          background: '#3178c6',
                          marginRight: 4,
                        }}
                      />
                      {repo.language}
                    </span>
                  )}
                  <span>★ {repo.stars.toLocaleString()}</span>
                  <span>Issues {repo.openIssues}</span>
                  <span>更新 {repo.lastSync}</span>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                <span className={statusLabel[repo.syncStatus].className}>
                  {statusLabel[repo.syncStatus].text}
                </span>
                <button type="button" className="gh-btn gh-btn-sm" onClick={() => navigate(`/repos/${repo.id}`)}>
                  详情
                </button>
                {repo.htmlUrl && (
                  <a className="gh-btn gh-btn-sm" href={repo.htmlUrl} target="_blank" rel="noreferrer">
                    <MarkGithubIcon size={14} />
                  </a>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </PageShell>
  )
}
