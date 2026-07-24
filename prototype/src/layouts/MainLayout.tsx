import { Outlet } from 'react-router-dom'
import { LayoutProvider, useLayout } from '../context/LayoutContext'
import { RepoProvider } from '../context/RepoContext'
import AppSidebar from '../components/layout/AppSidebar'
import ContextPanel from '../components/layout/ContextPanel'
import GitHubHeader from '../components/layout/GitHubHeader'
import { ChevronLeftIcon, ChevronRightIcon } from '@primer/octicons-react'

function LayoutBody() {
  const { leftCollapsed, rightCollapsed, toggleLeft, toggleRight } = useLayout()

  return (
    <div className="gh-app">
      <GitHubHeader />
      <div className="gh-body">
        <div className={`gh-sidebar-wrap${leftCollapsed ? ' collapsed' : ''}`}>
          <button
            type="button"
            className="gh-sidebar-toggle"
            onClick={toggleLeft}
            aria-label={leftCollapsed ? '展开左侧栏' : '收起左侧栏'}
            title={leftCollapsed ? '展开导航' : '收起导航'}
          >
            {leftCollapsed ? <ChevronRightIcon size={16} /> : <ChevronLeftIcon size={16} />}
          </button>
          {!leftCollapsed && <AppSidebar />}
        </div>

        <div className="gh-main-wrap">
          <Outlet />
          <div className={`gh-aside-wrap${rightCollapsed ? ' collapsed' : ''}`}>
            <button
              type="button"
              className="gh-aside-toggle"
              onClick={toggleRight}
              aria-label={rightCollapsed ? '展开右侧栏' : '收起右侧栏'}
              title={rightCollapsed ? '展开侧栏' : '收起侧栏'}
            >
              {rightCollapsed ? <ChevronLeftIcon size={16} /> : <ChevronRightIcon size={16} />}
            </button>
            {!rightCollapsed && <ContextPanel />}
          </div>
        </div>
      </div>
    </div>
  )
}

export default function MainLayout() {
  return (
    <RepoProvider>
      <LayoutProvider>
        <LayoutBody />
      </LayoutProvider>
    </RepoProvider>
  )
}
