/** TanStack Query cache key for the authenticated user's repository list. */
export const REPO_LIST_QUERY_KEY = ['repoList'] as const

/** TanStack Query cache key for a single repository. */
export function repoSingleQueryKey(repoId: string) {
  return ['repoSingle', repoId] as const
}