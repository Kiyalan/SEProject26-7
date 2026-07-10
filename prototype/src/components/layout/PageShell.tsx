import type { ReactNode } from 'react'

interface PageShellProps {
  title: string
  description?: string
  actions?: ReactNode
  children: ReactNode
}

export default function PageShell({ title, description, actions, children }: PageShellProps) {
  return (
    <div className="gh-main">
      <div className="gh-page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16 }}>
        <div>
          <h1 className="gh-page-title">{title}</h1>
          {description && <p className="gh-page-desc">{description}</p>}
        </div>
        {actions && <div style={{ flexShrink: 0 }}>{actions}</div>}
      </div>
      {children}
    </div>
  )
}
