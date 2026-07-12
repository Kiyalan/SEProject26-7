import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { REPO_LIST_QUERY_KEY } from '../config/QueryConfig'
import { fetchRepoList, fetchRepoSingle } from '../lib/api'
import type { Repository, RepositoryList } from '../lib/FrontendTypes'
import {
  getCurrentRepo,
  setCurrentRepo as setCurrentRepoService,
  syncRepoFromServer as syncRepoFromServerService,
  syncRepoListFromServer as syncRepoListFromServerService,
  type RepoListDeps,
} from '../service/RepoService'

interface RepoContextValue {
  repoList: RepositoryList
  currentRepoId: string
  currentRepo: Repository | null
  isRepoListPending: boolean
  isRepoListFetching: boolean
  setCurrentRepo: (id: string) => void
  syncRepoListFromServer: () => Promise<RepositoryList>
  syncRepoFromServer: (repoId: string) => Promise<Repository>
}

const RepoContext = createContext<RepoContextValue | null>(null)

function repoQueryKey(repoId: string) {
  return ['repo', repoId] as const
}

export function RepoProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [currentRepoId, setCurrentRepoId] = useState('')

  const {
    data: repoList = [],
    isPending: isRepoListPending,
    isFetching: isRepoListFetching,
  } = useQuery({
    queryKey: REPO_LIST_QUERY_KEY,
    queryFn: () => fetchRepoList(),
  })

  const { data: currentRepoFromServer } = useQuery({
    queryKey: repoQueryKey(currentRepoId),
    queryFn: () => fetchRepoSingle(currentRepoId),
    enabled: !!currentRepoId,
  })

  const deps = useMemo<RepoListDeps>(
    () => ({
      getState: () => ({
        repoList: queryClient.getQueryData<RepositoryList>(REPO_LIST_QUERY_KEY) ?? repoList,
        currentRepoId,
      }),
      mutators: {
        setRepoList: (updater) => {
          queryClient.setQueryData<RepositoryList>(REPO_LIST_QUERY_KEY, (prev = []) =>
            typeof updater === 'function' ? updater(prev) : updater,
          )
        },
        setCurrentRepoId,
      },
    }),
    [queryClient, repoList, currentRepoId],
  )

  useEffect(() => {
    if (!currentRepoId && repoList.length > 0) {
      setCurrentRepoId(repoList[0].id)
    }
  }, [repoList, currentRepoId])

  const setCurrentRepo = useCallback(
    (id: string) => setCurrentRepoService(deps, id),
    [deps],
  )

  const syncRepoListFromServer = useCallback(
    () => syncRepoListFromServerService(deps),
    [deps],
  )

  const syncRepoFromServer = useCallback(
    async (repoId: string) => {
      const repo = await syncRepoFromServerService(deps, repoId)
      queryClient.setQueryData(repoQueryKey(repoId), repo)
      return repo
    },
    [deps, queryClient],
  )

  const currentRepo =
    currentRepoFromServer ?? getCurrentRepo({ repoList, currentRepoId })

  const value = useMemo<RepoContextValue>(
    () => ({
      repoList,
      currentRepoId,
      currentRepo,
      isRepoListPending,
      isRepoListFetching,
      setCurrentRepo,
      syncRepoListFromServer,
      syncRepoFromServer,
    }),
    [
      repoList,
      currentRepoId,
      currentRepo,
      isRepoListPending,
      isRepoListFetching,
      setCurrentRepo,
      syncRepoListFromServer,
      syncRepoFromServer,
    ],
  )

  return <RepoContext.Provider value={value}>{children}</RepoContext.Provider>
}

export function useRepoContext() {
  const ctx = useContext(RepoContext)
  if (!ctx) throw new Error('useRepoContext must be used within RepoProvider')
  return ctx
}
