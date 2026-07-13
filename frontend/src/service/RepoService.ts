import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { REPO_LIST_QUERY_KEY, repoSingleQueryKey } from '../config/QueryConfig'
import { fetchRepoList as fetchRepoListApi, fetchRepoSingle as fetchRepoSingleApi } from '../lib/api'
import type { Repository, RepositoryList } from '../lib/FrontendTypes'

export interface RepoListState {
  repoList: RepositoryList
  currentRepoId: string
}

export interface RepoListMutators {
  setRepoListCache: (
    updater: RepositoryList | ((prev: RepositoryList) => RepositoryList),
  ) => void
  getRepoListCache: () => RepositoryList | undefined
  fetchRepoList: () => Promise<RepositoryList>
  getRepoSingleCache: (repoId: string) => Repository | undefined
  setRepoSingleCache: (repoId: string, repo: Repository) => void
  fetchRepoSingle: (repoId: string) => Promise<Repository>
  setCurrentRepoListId: (id: string | ((prev: string) => string)) => void
  getCurrentRepoListId: () => string
  getCurrentRepo: () => Repository | null
}

export interface RepoListDeps {
  getState: () => RepoListState
  mutators: RepoListMutators
}

export function resolveDefaultCurrentRepoId(
  currentRepoId: string,
  repoList: RepositoryList,
): string {
  return currentRepoId || repoList[0]?.id || ''
}

export function mergeRepoIntoList(repoList: RepositoryList, repo: Repository): RepositoryList {
  const idx = repoList.findIndex((r) => r.id === repo.id)
  if (idx >= 0) {
    const next = [...repoList]
    next[idx] = repo
    return next
  }
  // 容错：列表中找不到时追加（权限变化或缓存过期等场景）
  return [...repoList, repo]
}

export function createRepoListDeps() {
  const [currentRepoId, setCurrentRepoId] = useState('')
  const queryClient = useQueryClient()

  const {
    data: repoList = [],
    isPending,
    isFetching,
  } = useQuery({
    queryKey: REPO_LIST_QUERY_KEY,
    queryFn: () => fetchRepoListApi(),
  })

  const deps = useMemo<RepoListDeps>(
    () => ({
      getState: () => ({
        repoList:
          queryClient.getQueryData<RepositoryList>(REPO_LIST_QUERY_KEY) ?? repoList,
        currentRepoId,
      }),
      mutators: {
        // RepoList
        getRepoListCache: (): RepositoryList | undefined =>
          queryClient.getQueryData<RepositoryList>(REPO_LIST_QUERY_KEY),
        setRepoListCache: (
          updater: RepositoryList | ((prev: RepositoryList) => RepositoryList),
        ) => {
          queryClient.setQueryData<RepositoryList>(REPO_LIST_QUERY_KEY, (prev = []) =>
            typeof updater === 'function' ? updater(prev) : updater,
          )
        },
        fetchRepoList: async (): Promise<RepositoryList> => {
          const list = await queryClient.fetchQuery({
            queryKey: REPO_LIST_QUERY_KEY,
            queryFn: () => fetchRepoListApi(),
          })
          setCurrentRepoId((prev) => resolveDefaultCurrentRepoId(prev, list))
          return list
        },
        // RepoSingle
        getRepoSingleCache: (repoId: string): Repository | undefined =>
          queryClient.getQueryData<Repository>(repoSingleQueryKey(repoId)),
        setRepoSingleCache: (repoId: string, repo: Repository): void => {
          queryClient.setQueryData(repoSingleQueryKey(repoId), repo)
        },
        fetchRepoSingle: async (repoId: string): Promise<Repository> => {
          const repo = await queryClient.fetchQuery({
            queryKey: repoSingleQueryKey(repoId),
            queryFn: () => fetchRepoSingleApi(repoId),
          })
          queryClient.setQueryData<RepositoryList>(REPO_LIST_QUERY_KEY, (prev = []) =>
            mergeRepoIntoList(prev, repo),
          )
          setCurrentRepoId(repo.id)
          return repo
        },
        // currentRepoId
        getCurrentRepoListId: () => currentRepoId,
        setCurrentRepoListId: setCurrentRepoId,
        getCurrentRepo: (): Repository | null => {
          const list =
            queryClient.getQueryData<RepositoryList>(REPO_LIST_QUERY_KEY) ?? repoList
          return list.find((r) => r.id === currentRepoId) ?? null
        },
      },
    }),
    [queryClient, currentRepoId, repoList],
  )

  useEffect(() => {
    if (!currentRepoId && repoList.length > 0) {
      setCurrentRepoId(resolveDefaultCurrentRepoId(currentRepoId, repoList))
    }
  }, [repoList, currentRepoId])

  return {
    deps,
    repoList,
    currentRepoId,
    isRepoListPending: isPending,
    isRepoListFetching: isFetching,
  }
}
