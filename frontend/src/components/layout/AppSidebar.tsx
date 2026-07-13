import {
  BookIcon,
  GearIcon,
  IssueOpenedIcon,
  MarkGithubIcon,
  CommentDiscussionIcon,
} from '@primer/octicons-react'
import { NavLink } from 'react-router-dom'
import { useRepoContext } from '../../context/RepoContext'

const navItems = [
  { to: '/repos', label: '仓库', icon: MarkGithubIcon, section: '概览' },
  { to: '/chat', label: '智能问答', icon: CommentDiscussionIcon, section: '功能' },
  { to: '/issues', label: 'Issue 分析', icon: IssueOpenedIcon, section: '功能' },
  { to: '/knowledge', label: '知识库', icon: BookIcon, section: '功能' },
  { to: '/settings', label: '设置', icon: GearIcon, section: '系统' },
]

export default function AppSidebar() {
  const { currentRepo } = useRepoContext()

  let lastSection = ''

  return (
    <aside className="gh-sidebar">
      {currentRepo && (
        <div style={{ padding: '0 12px 12px' }}>
          <div className="gh-muted" style={{ fontSize: 12, marginBottom: 4 }}>
            当前仓库
          </div>
          <NavLink to={`/repos/${currentRepo.id}`} className="gh-link" style={{ fontWeight: 600 }}>
            {currentRepo.fullName}
          </NavLink>
          {currentRepo.description && (
            <p className="gh-muted" style={{ margin: '4px 0 0', fontSize: 12 }}>
              {currentRepo.description}
            </p>
          )}
        </div>
      )}

      <ul className="gh-sidebar-nav">
        {navItems.map((item) => {
          const showSection = item.section !== lastSection
          lastSection = item.section
          const Icon = item.icon
          return (
            <li key={item.to}>
              {showSection && <div className="gh-sidebar-section">{item.section}</div>}
              <NavLink
                to={item.to}
                className={({ isActive }) => `gh-sidebar-link${isActive ? ' active' : ''}`}
              >
                <Icon size={16} />
                {item.label}
              </NavLink>
            </li>
          )
        })}
      </ul>
    </aside>
  )
}
