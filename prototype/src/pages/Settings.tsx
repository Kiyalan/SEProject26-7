import { useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import { fetchBackendHealth, fetchLlmConfig } from '../lib/api'

export default function Settings() {
  const [llm, setLlm] = useState<{
    configured: boolean
    model: string
    provider?: string
    baseUrl?: string
  } | null>(null)
  const [health, setHealth] = useState<{
    pid: number
    startedAt: string
    llmConfigured: boolean
  } | null>(null)
  const [loading, setLoading] = useState(false)

  const refresh = () => {
    setLoading(true)
    Promise.all([fetchLlmConfig(), fetchBackendHealth()])
      .then(([cfg, h]) => {
        setLlm(cfg)
        setHealth(h)
      })
      .catch(() => {
        setLlm(null)
        setHealth(null)
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    refresh()
  }, [])

  const mismatch = health && llm && health.llmConfigured !== llm.configured

  return (
    <PageShell
      title="Settings"
      description="GitHub 接入与 AI 服务配置"
      actions={
        <button type="button" className="gh-btn" onClick={refresh} disabled={loading}>
          {loading ? '刷新中…' : '刷新状态'}
        </button>
      }
    >
      <div className="gh-grid-2">
        <div className="gh-box">
          <div className="gh-box-header">GitHub 接入</div>
          <div className="gh-box-body">
            <div className="gh-data-row">
              <span className="gh-muted">OAuth 状态</span>
              <span className="gh-label gh-label-green">已连接（登录后）</span>
            </div>
            {health && (
              <>
                <div className="gh-data-row">
                  <span className="gh-muted">后端 PID</span>
                  <span>{health.pid}</span>
                </div>
                <div className="gh-data-row">
                  <span className="gh-muted">启动时间 (UTC)</span>
                  <span style={{ fontSize: 12 }}>{health.startedAt}</span>
                </div>
              </>
            )}
            <p className="gh-muted" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
              修改 <code>backend/.env</code> 后点击「刷新状态」。若仍不对，运行 <code>npm run kill:ports</code> 后重启后端。
              也可访问 <a className="gh-link" href="http://localhost:8000/api/health" target="_blank" rel="noreferrer">/api/health</a> 核对。
            </p>
          </div>
        </div>

        <div className="gh-box">
          <div className="gh-box-header">OpenRouter LLM</div>
          <div className="gh-box-body">
            {mismatch && (
              <p className="gh-label gh-label-orange" style={{ marginBottom: 12 }}>
                配置不一致：请重启后端（旧进程可能仍在占 8000 端口）
              </p>
            )}
            <div className="gh-data-row">
              <span className="gh-muted">状态</span>
              <span className={`gh-label${llm?.configured ? ' gh-label-green' : ' gh-label-orange'}`}>
                {llm?.configured ? '已配置' : '未配置（检索摘要模式）'}
              </span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">模型</span>
              <span>{llm?.model ?? '—'}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">提供商</span>
              <span>{llm?.provider ?? '—'}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">Base URL</span>
              <span style={{ fontSize: 12 }}>{llm?.baseUrl ?? '—'}</span>
            </div>

            <h4 style={{ margin: '16px 0 8px', fontSize: 14 }}>配置位置</h4>
            <p className="gh-muted" style={{ margin: 0, fontSize: 13, lineHeight: 1.7 }}>
              文件：<code>prototype/backend/.env</code>
              <br />
              Key：<code>LLM_API_KEY=sk-or-v1-...</code>（在 openrouter.ai/keys 创建）
              <br />
              模型：<code>LLM_MODEL=tencent/hy3:free</code>
            </p>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
