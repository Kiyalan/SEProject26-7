import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { projectDisplayNameCamel } from '../../config/BaseConfig'

interface LayoutContextValue {
  leftCollapsed: boolean
  rightCollapsed: boolean
  toggleLeft: () => void
  toggleRight: () => void
}

const LayoutContext = createContext<LayoutContextValue | null>(null)

const STORAGE_KEY = `${projectDisplayNameCamel}Layout`

function loadLayout(): { leftCollapsed: boolean; rightCollapsed: boolean } {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch {
    /* ignore */
  }
  const narrow = typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches
  return { leftCollapsed: narrow, rightCollapsed: narrow }
}

export function LayoutProvider({ children }: { children: ReactNode }) {
  const [leftCollapsed, setLeftCollapsed] = useState(() => loadLayout().leftCollapsed)
  const [rightCollapsed, setRightCollapsed] = useState(() => loadLayout().rightCollapsed)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ leftCollapsed, rightCollapsed }))
  }, [leftCollapsed, rightCollapsed])

  const toggleLeft = useCallback(() => setLeftCollapsed((v) => !v), [])
  const toggleRight = useCallback(() => setRightCollapsed((v) => !v), [])

  return (
    <LayoutContext.Provider value={{ leftCollapsed, rightCollapsed, toggleLeft, toggleRight }}>
      {children}
    </LayoutContext.Provider>
  )
}

export function useLayout() {
  const ctx = useContext(LayoutContext)
  if (!ctx) throw new Error('useLayout must be used within LayoutProvider')
  return ctx
}
