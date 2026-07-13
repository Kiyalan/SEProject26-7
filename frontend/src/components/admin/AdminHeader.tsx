import { ShieldLockIcon, SignOutIcon } from '@primer/octicons-react'
import { useNavigate } from 'react-router-dom'
import { clearAdminAuth, getAdminUsername } from '../../lib/adminAuth'

export default function AdminHeader() {
  const navigate = useNavigate()
  const username = getAdminUsername() || '管理员'

  const logout = () => {
    clearAdminAuth()
    navigate('/admin/login', { replace: true })
  }

  return (
    <header className="admin-header">
      <div className="admin-header-brand">
        <ShieldLockIcon size={20} />
        <span>RepoPilot 运维后台</span>
        <span className="admin-header-badge">UC7</span>
      </div>
      <div className="admin-header-actions">
        <span className="gh-muted" style={{ fontSize: 13 }}>
          {username}
        </span>
        <button type="button" className="gh-btn gh-btn-sm" onClick={() => navigate('/repos')}>
          返回用户端
        </button>
        <button type="button" className="gh-btn gh-btn-sm" onClick={logout}>
          <SignOutIcon size={14} />
          退出
        </button>
      </div>
    </header>
  )
}
