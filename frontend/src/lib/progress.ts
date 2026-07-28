import {
  fetchRepoProgress as fetchRepoProgressApi,
  type ProgressSnapshot,
} from '../api/generated'

export type BuildProgressSnapshot = ProgressSnapshot

export async function fetchRepoProgress(repoId: string): Promise<{
  knowledge: BuildProgressSnapshot
  issues: BuildProgressSnapshot
}> {
  const { data } = await fetchRepoProgressApi({ path: { repoId } })
  return { knowledge: data.knowledge, issues: data.issues }
}

export function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
