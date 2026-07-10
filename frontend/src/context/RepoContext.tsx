import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { fetchRepositories } from '../lib/api'
import type { Repository } from '../lib/FrontendTypes'

interface RepoContextValue {
  repos: Repository[]
  repoId: string
  setRepoId: (id: string) => void
  currentRepo: Repository | null
  loading: boolean
  refreshRepos: () => Promise<void>
}

const RepoContext = createContext<RepoContextValue | null>(null)

export function RepoProvider({ children }: { children: ReactNode }) {
  const [repos, setRepos] = useState<Repository[]>([])
  const [repoId, setRepoId] = useState('')
  const [loading, setLoading] = useState(true)

  const refreshRepos = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchRepositories()
      setRepos(data.items)
      setRepoId((prev) => prev || data.items[0]?.id || '')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refreshRepos().catch(() => setLoading(false))
  }, [refreshRepos])

  const currentRepo = repos.find((r) => r.id === repoId) ?? null

  return (
    <RepoContext.Provider
      value={{ repos, repoId, setRepoId, currentRepo, loading, refreshRepos }}
    >
      {children}
    </RepoContext.Provider>
  )
}

export function useRepoContext() {
  const ctx = useContext(RepoContext)
  if (!ctx) throw new Error('useRepoContext must be used within RepoProvider')
  return ctx
}
