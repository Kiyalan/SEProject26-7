import { useEffect, useState } from 'react'
import { Spin } from 'antd'
import { fetchPortfolioOverview } from '../lib/api'
import type { PortfolioOverview } from '../lib/BackendTypes'

export default function PortfolioPanel() {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<PortfolioOverview | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open || data) return
    setLoading(true)
    fetchPortfolioOverview()
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false))
  }, [open, data])

  return (
    <div className="gh-box" style={{ marginBottom: 16 }}>
      <div className="gh-box-header">
        <span>多仓库总览（样本）</span>
        <button type="button" className="gh-btn gh-btn-sm" onClick={() => setOpen((v) => !v)}>
          {open ? '收起' : '展开'}
        </button>
      </div>
      {open && (
        <div className="gh-box-body">
          {loading && (
            <div style={{ textAlign: 'center', padding: 24 }}>
              <Spin />
            </div>
          )}
          {!loading && data && (
            <>
              <div className="gh-grid-3" style={{ marginBottom: 16 }}>
                <div className="gh-stat">
                  <div className="gh-stat-value">{data.summary.repoCount}</div>
                  <div className="gh-stat-label">仓库数</div>
                </div>
                <div className="gh-stat">
                  <div className="gh-stat-value">{data.summary.indexedCount}</div>
                  <div className="gh-stat-label">已建知识库</div>
                </div>
                <div className="gh-stat">
                  <div className="gh-stat-value">{data.summary.indexRate}%</div>
                  <div className="gh-stat-label">索引覆盖率</div>
                </div>
              </div>
              <div className="gh-grid-2">
                <div>
                  <h4 style={{ margin: '0 0 8px', fontSize: 14 }}>语言分布（GitHub 主语言）</h4>
                  {data.languageBreakdown.map((row) => (
                    <div key={row.language} className="gh-data-row">
                      <span>{row.language}</span>
                      <span className="gh-muted">
                        {row.count} 个 · {row.percent}%
                      </span>
                    </div>
                  ))}
                </div>
                <div>
                  <h4 style={{ margin: '0 0 8px', fontSize: 14 }}>技术栈聚类（规则）</h4>
                  {Object.entries(data.clusters).map(([name, repos]) => (
                    <div key={name} style={{ marginBottom: 8, fontSize: 13 }}>
                      <strong>{name}</strong>
                      <div className="gh-muted">{repos.join(' · ') || '—'}</div>
                    </div>
                  ))}
                </div>
              </div>
              <h4 style={{ margin: '16px 0 8px', fontSize: 14 }}>仓库列表（按最近 push）</h4>
              <div style={{ maxHeight: 220, overflowY: 'auto' }}>
                {data.repos.slice(0, 12).map((repo) => (
                  <div key={repo.repoId} className="gh-data-row">
                    <span>{repo.fullName}</span>
                    <span className="gh-muted" style={{ fontSize: 12 }}>
                      {repo.language} · ★{repo.stars}
                      {repo.knowledge.indexed ? (
                        <span className="gh-label gh-label-green" style={{ marginLeft: 6 }}>
                          已索引
                        </span>
                      ) : (
                        <span className="gh-label" style={{ marginLeft: 6 }}>
                          未索引
                        </span>
                      )}
                    </span>
                  </div>
                ))}
              </div>
              <ul className="gh-muted" style={{ margin: '12px 0 0', paddingLeft: 18, fontSize: 12 }}>
                {data.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            </>
          )}
          {!loading && !data && <p className="gh-muted">加载失败，请确认已登录 GitHub</p>}
        </div>
      )}
    </div>
  )
}
