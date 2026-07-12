import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { fetchKnowledge } from '../../lib/api'
import { useRepoContext } from '../../context/RepoContext'
import QuickActions from '../QuickActions'

const LANG_COLORS: Record<string, string> = {
  TypeScript: '#3178c6',
  JavaScript: '#f1e05a',
  Python: '#3572A5',
  CSS: '#563d7c',
  HTML: '#e34c26',
  JSON: '#292929',
  Markdown: '#083fa1',
}

export default function ContextPanel() {
  const location = useLocation()
  const { currentRepoId, currentRepo } = useRepoContext()
  const [knowledge, setKnowledge] = useState<Awaited<ReturnType<typeof fetchKnowledge>> | null>(null)

  useEffect(() => {
    if (!currentRepoId) return
    fetchKnowledge(currentRepoId)
      .then(setKnowledge)
      .catch(() => setKnowledge(null))
  }, [currentRepoId, location.pathname])

  const showActions = !location.pathname.startsWith('/settings')

  const langEntries = Object.entries(knowledge?.languages || {})
  const langTotal = langEntries.reduce((s, [, c]) => s + c, 0) || 1

  return (
    <aside className="gh-aside">
      {currentRepo && (
        <div className="gh-aside-section">
          <div className="gh-box">
            <div className="gh-box-header">仓库概览</div>
            <div className="gh-box-body" style={{ padding: '8px 16px' }}>
              <div className="gh-data-row">
                <span className="gh-muted">语言</span>
                <span>{currentRepo.language}</span>
              </div>
              <div className="gh-data-row">
                <span className="gh-muted">Stars</span>
                <span>{currentRepo.stars.toLocaleString()}</span>
              </div>
              <div className="gh-data-row">
                <span className="gh-muted">Open Issues</span>
                <span>{currentRepo.openIssues}</span>
              </div>
              <div className="gh-data-row">
                <span className="gh-muted">默认分支</span>
                <span>{currentRepo.defaultBranch || 'main'}</span>
              </div>
              {currentRepo.htmlUrl && (
                <a
                  className="gh-link"
                  href={currentRepo.htmlUrl}
                  target="_blank"
                  rel="noreferrer"
                  style={{ display: 'block', marginTop: 8, fontSize: 13 }}
                >
                  在 GitHub 上查看 →
                </a>
              )}
            </div>
          </div>
        </div>
      )}

      {knowledge && knowledge.status === 'ready' && (
        <div className="gh-aside-section">
          <div className="gh-box">
            <div className="gh-box-header">知识库索引</div>
            <div className="gh-box-body" style={{ padding: '8px 16px' }}>
              <div className="gh-data-row">
                <span className="gh-muted">状态</span>
                <span className="gh-label gh-label-green">已就绪</span>
              </div>
              <div className="gh-data-row">
                <span className="gh-muted">文件数</span>
                <span>{knowledge.fileCount}</span>
              </div>
              <div className="gh-data-row">
                <span className="gh-muted">检索片段</span>
                <span>{knowledge.chunkCount}</span>
              </div>
              {knowledge.indexedAt && (
                <div className="gh-data-row">
                  <span className="gh-muted">索引时间</span>
                  <span style={{ fontSize: 12 }}>{knowledge.indexedAt}</span>
                </div>
              )}
              {langEntries.length > 0 && (
                <>
                  <div className="gh-lang-bar">
                    {langEntries.map(([lang, count]) => (
                      <div
                        key={lang}
                        className="gh-lang-segment"
                        style={{
                          width: `${(count / langTotal) * 100}%`,
                          background: LANG_COLORS[lang] || '#8b949e',
                        }}
                        title={`${lang}: ${count}`}
                      />
                    ))}
                  </div>
                  <div className="gh-muted" style={{ fontSize: 12 }}>
                    {langEntries.map(([l, c]) => `${l} ${Math.round((c / langTotal) * 100)}%`).join(' · ')}
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {showActions && <QuickActions compact />}
    </aside>
  )
}
