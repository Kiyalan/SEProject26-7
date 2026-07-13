import { ShieldLockIcon } from '@primer/octicons-react'
import { Alert, Form, Input } from 'antd'
import { useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import {
  DEMO_ADMIN,
  adminLogin,
  getAdminLockState,
  isAdminAuthenticated,
} from '../../lib/adminAuth'

export default function AdminLogin() {
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const lock = getAdminLockState()

  if (isAdminAuthenticated()) {
    return <Navigate to="/admin" replace />
  }

  const onFinish = (values: { username: string; password: string }) => {
    setLoading(true)
    setError(null)
    const result = adminLogin(values.username, values.password)
    setLoading(false)

    if (result.ok) {
      window.location.href = '/admin'
      return
    }

    if (result.reason === 'empty_fields') {
      setError('请输入账号与密码')
    } else if (result.reason === 'locked') {
      setError(`账号已临时锁定，请 ${result.minutesLeft} 分钟后再试`)
    } else if (result.reason === 'invalid_credentials') {
      setError(`密码错误，剩余 ${result.remaining} 次尝试机会`)
    }
  }

  return (
    <div className="admin-login-page">
      <div className="gh-box admin-login-card">
        <div className="gh-box-body" style={{ padding: 32 }}>
          <div style={{ textAlign: 'center', marginBottom: 24 }}>
            <ShieldLockIcon size={40} />
            <h1 style={{ fontSize: 22, margin: '12px 0 6px' }}>管理员登录</h1>
            <p className="gh-muted" style={{ margin: 0 }}>
              RepoPilot 全平台运维管理（UC7）
            </p>
          </div>

          {lock.locked && (
            <Alert
              type="warning"
              showIcon
              message="账号已临时锁定"
              description={`连续登录失败次数过多，请 ${lock.minutesLeft} 分钟后再试。`}
              style={{ marginBottom: 16 }}
            />
          )}

          {error && (
            <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />
          )}

          <Form layout="vertical" onFinish={onFinish} disabled={lock.locked}>
            <Form.Item
              label="管理员账号"
              name="username"
              rules={[{ required: true, message: '请输入账号' }]}
            >
              <Input placeholder="admin" autoComplete="username" />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password placeholder="请输入密码" autoComplete="current-password" />
            </Form.Item>
            <button
              type="submit"
              className="gh-btn gh-btn-primary"
              style={{ width: '100%', padding: '10px 16px' }}
              disabled={loading || lock.locked}
            >
              {loading ? '登录中…' : '登录运维后台'}
            </button>
          </Form>

          <div className="admin-login-hint">
            <p className="gh-muted" style={{ fontSize: 12, margin: '16px 0 8px' }}>
              演示账号：<code>{DEMO_ADMIN.username}</code> / <code>{DEMO_ADMIN.password}</code>
            </p>
            <p className="gh-muted" style={{ fontSize: 12, margin: 0 }}>
              普通用户请使用 <Link to="/login">GitHub 登录</Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
