import { MarkGithubIcon } from '@primer/octicons-react'
import { Alert } from 'antd'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import { isAuthenticated, startGithubLogin } from '../lib/auth'

export default function Login() {
  const [params] = useSearchParams()
  const error = params.get('error')

  if (isAuthenticated()) {
    return <Navigate to="/repos" replace />
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--gh-canvas-subtle)',
        padding: 24,
      }}
    >
      <div className="gh-box" style={{ width: 400, textAlign: 'center' }}>
        <div className="gh-box-body" style={{ padding: 32 }}>
          <MarkGithubIcon size={48} />
          <h1 style={{ fontSize: 24, margin: '16px 0 8px' }}>Sign in to RepoPilot</h1>
          <p className="gh-muted" style={{ margin: '0 0 24px' }}>
            GitHub 仓库问答与 Issue 分析系统
          </p>

          {error && (
            <Alert type="error" showIcon message="GitHub 授权失败" description={error} style={{ marginBottom: 16, textAlign: 'left' }} />
          )}

          <p className="gh-muted" style={{ fontSize: 13, marginBottom: 24 }}>
            授权后可读取仓库列表、Issue 数据，并通过 API 执行提交与 PR 操作。
          </p>

          <button type="button" className="gh-btn gh-btn-primary" style={{ width: '100%', padding: '10px 16px' }} onClick={startGithubLogin}>
            <MarkGithubIcon size={16} />
            使用 GitHub 登录
          </button>

          <p className="gh-muted" style={{ fontSize: 12, marginTop: 16, marginBottom: 8 }}>
            首次使用需在 backend/.env 配置 GitHub OAuth App
          </p>
          <p className="gh-muted" style={{ fontSize: 12, margin: 0 }}>
            系统管理员请前往 <Link to="/admin/login">运维后台登录</Link>
          </p>
        </div>
      </div>
    </div>
  )
}
