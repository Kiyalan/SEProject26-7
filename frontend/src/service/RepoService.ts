import { fetchRepoList, fetchRepoSingle } from '../lib/api'
import type { Repository, RepositoryList } from '../lib/FrontendTypes'

export interface RepoListState {
  repoList: RepositoryList
  currentRepoId: string
}

export interface RepoListMutators {
  setRepoList: (repos: RepositoryList | ((prev: RepositoryList) => RepositoryList)) => void
  setCurrentRepoId: (id: string | ((prev: string) => string)) => void
}

export interface RepoListDeps {
  getState: () => RepoListState
  mutators: RepoListMutators
}

export function getCurrentRepo(state: RepoListState): Repository | null {
  return state.repoList.find((r) => r.id === state.currentRepoId) ?? null
}

export function setCurrentRepo(deps: RepoListDeps, id: string): void {
  deps.mutators.setCurrentRepoId(id)
}

export async function syncRepoListFromServer(deps: RepoListDeps): Promise<RepositoryList> {
  const repoList = await fetchRepoList()
  deps.mutators.setRepoList(repoList)
  deps.mutators.setCurrentRepoId((prev) => prev || repoList[0]?.id || '')
  return repoList
}

export async function syncRepoFromServer(deps: RepoListDeps, repoId: string): Promise<Repository> {
  const repo = await fetchRepoSingle(repoId)
  deps.mutators.setRepoList((prev) => {
    const idx = prev.findIndex((r) => r.id === repo.id)
    if (idx >= 0) {
      const next = [...prev]
      next[idx] = repo
      return next
    }
    // 容错处理，对应找不到对应仓库或者无权限访问的情况，在恶意用户或者数据过期的情况下触发。
    return [...prev, repo]
  })
  deps.mutators.setCurrentRepoId(repoId)
  return repo
}
