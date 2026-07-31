import type { ReactNode } from 'react'

type SettingSectionProps = {
  title: string
  children: ReactNode
  footer?: ReactNode
}

export default function SettingSection({ title, children, footer }: SettingSectionProps) {
  return (
    <section className="setting-section">
      <div className="gh-box">
        <div className="gh-box-header">{title}</div>
        <div className="gh-box-body setting-section-body">
          {children}
          {footer ? <div className="setting-section-footer">{footer}</div> : null}
        </div>
      </div>
    </section>
  )
}

export function SettingStatusRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="setting-status-row">
      <span className="gh-muted">{label}</span>
      <span>{value}</span>
    </div>
  )
}
