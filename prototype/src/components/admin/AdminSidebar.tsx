import {
  AlertIcon,
  ChecklistIcon,
  GraphIcon,
  HistoryIcon,
  PeopleIcon,
  SyncIcon,
  UploadIcon,
} from '@primer/octicons-react'
import { NavLink } from 'react-router-dom'

const navItems = [
  { to: '/admin', label: '平台总览', icon: GraphIcon, end: true },
  { to: '/admin/sync-logs', label: '同步任务日志', icon: SyncIcon },
  { to: '/admin/data-integrity', label: '数据完整性', icon: ChecklistIcon },
  { to: '/admin/sync-failures', label: '故障排查', icon: AlertIcon },
  { to: '/admin/faq-export', label: 'FAQ 导出', icon: UploadIcon },
  { to: '/admin/users', label: '用户管理', icon: PeopleIcon },
  { to: '/admin/audit-logs', label: '运维日志', icon: HistoryIcon },
]

export default function AdminSidebar() {
  return (
    <aside className="admin-sidebar">
      <div className="admin-sidebar-title">运维管理</div>
      <ul className="admin-sidebar-nav">
        {navItems.map((item) => {
          const Icon = item.icon
          return (
            <li key={item.to}>
              <NavLink
                to={item.to}
                end={item.end}
                className={({ isActive }) => `admin-sidebar-link${isActive ? ' active' : ''}`}
              >
                <Icon size={16} />
                {item.label}
              </NavLink>
            </li>
          )
        })}
      </ul>
      <div className="admin-sidebar-foot">
        <p className="gh-muted" style={{ fontSize: 12, margin: 0 }}>
          演示数据 · 仅前端 Mock
        </p>
      </div>
    </aside>
  )
}
