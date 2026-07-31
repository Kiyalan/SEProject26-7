import {
  createContext,
  useContext,
  useMemo,
  type ReactNode,
} from 'react'
import type { Repository, RepositoryList } from '../lib/FrontendTypes'
import * as RepoService from '../service/RepoService'

interface RepoContextValue {
  repoList: RepositoryList
  currentRepoId: string
  currentRepo: Repository | null
  isRepoListPending: boolean
  isRepoListFetching: boolean
  setCurrentRepo: (id: string) => void
  syncRepoList: () => Promise<RepositoryList>
  syncRepo: (repoId: string) => Promise<Repository>
}

const RepoContext = createContext<RepoContextValue | null>(null)

export function RepoProvider({ children }: { children: ReactNode }) {
  const { deps, repoList, currentRepoId, isRepoListPending, isRepoListFetching } =
    RepoService.createRepoListDeps()

  const value = useMemo<RepoContextValue>(
    () => ({
      repoList,
      currentRepoId,
      currentRepo: deps.mutators.getCurrentRepo(),
      isRepoListPending,
      isRepoListFetching,
      setCurrentRepo: (id) => deps.mutators.setCurrentRepoListId(id),
      syncRepoList: () => deps.mutators.fetchRepoList(),
      syncRepo: (repoId) => deps.mutators.fetchRepoSingle(repoId),
    }),
    [repoList, currentRepoId, isRepoListPending, isRepoListFetching, deps],
  )

  return <RepoContext.Provider value={value}>{children}</RepoContext.Provider>
}

export function useRepoContext() {
  const ctx = useContext(RepoContext)
  if (!ctx) throw new Error('useRepoContext must be used within RepoProvider')
  return ctx
}
