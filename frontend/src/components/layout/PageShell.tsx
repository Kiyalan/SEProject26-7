import type { ReactNode } from 'react'

interface PageShellProps {
  title: string
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
}

export default function PageShell({ title, description, actions, children }: PageShellProps) {
  return (
    <div className="gh-main" style={{ padding: '20px 24px', background: '#f5f7fa', minHeight: '100%' }}>
      {/* 顶部标题栏：加大尺寸、强化视觉权重 */}
      <div
        className="gh-page-header"
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 24,
          background: '#ffffff',
          borderRadius: 12,
          boxShadow: '0 3px 16px rgba(0, 0, 0, 0.08)',
          borderTop: '3px solid #165DFF',
          padding: '30px 32px',
          marginBottom: 32,
        }}
      >
        <div>
          <h1
            className="gh-page-title"
            style={{ fontSize: 26, fontWeight: 700, margin: '0 0 8px 0', color: '#111827' }}
          >
            {title}
          </h1>
          {description && (
            <p
              className="gh-page-desc"
              style={{ fontSize: 14, color: '#6B7280', margin: 0, lineHeight: 1.6 }}
            >
              {description}
            </p>
          )}
        </div>
        {actions && (
          <div style={{ flexShrink: 0 }}>
            {actions}
          </div>
        )}
      </div>

      {/* 页面内容区 */}
      <div>{children}</div>
    </div>
  )
}