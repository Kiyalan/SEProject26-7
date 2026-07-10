import { getToken, clearAuth } from './auth'
import type { Repository } from './FrontendTypes'
import type {
  ChatResponse,
  CommitCompareResult,
  GithubIssue,
  GithubUser,
  IndexedCommit,
  IssueAnalysis,
  KnowledgeOverview,
  KnowledgeSettings,
  PortfolioOverview,
} from './BackendTypes'

const API_BASE = import.meta.env.VITE_API_URL || ''

async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken()
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  })

  if (response.status === 401) {
    clearAuth()
    window.location.href = '/login'
    throw new Error('未登录')
  }

  const text = await response.text()

  if (!response.ok) {
    let message = `请求失败: ${response.status}`
    if (text) {
      try {
        const data = JSON.parse(text) as { detail?: string | { msg: string }[] }
        const detail = data.detail
        if (typeof detail === 'string') {
          message = detail
        } else if (Array.isArray(detail)) {
          message = detail.map((item) => item.msg).join('; ')
        } else if (detail && typeof detail === 'object') {
          message = JSON.stringify(detail)
        } else if (text !== 'Internal Server Error') {
          message = text
        }
      } catch {
        message = text
      }
    }
    throw new Error(message)
  }

  if (!text) {
    return undefined as T
  }

  return JSON.parse(text) as T
}

export function fetchCurrentUser() {
  return apiFetch<GithubUser>('/api/me')
}

export function fetchRepositories(page = 1) {
  return apiFetch<{ items: Repository[]; page: number; perPage: number }>(
    `/api/repos?page=${page}&per_page=50`,
  )
}

export function fetchRepository(repoId: string) {
  return apiFetch<Repository>(`/api/repos/${repoId}`)
}

export function fetchRepositoryIssues(
  repoId: string,
  options?: { state?: 'open' | 'closed' | 'all'; perPage?: number; page?: number },
) {
  const state = options?.state ?? 'all'
  const perPage = options?.perPage ?? 30
  const page = options?.page ?? 1
  return apiFetch<{
    items: GithubIssue[]
    total: number
    repoFullName: string
    openIssuesCount: number
    state: string
  }>(`/api/repos/${repoId}/issues?state=${state}&per_page=${perPage}&page=${page}`)
}

export function fetchRepositoryIssue(repoId: string, issueNumber: number) {
  return apiFetch<GithubIssue>(`/api/repos/${repoId}/issues/${issueNumber}`)
}

export function fetchKnowledgePolicy(repoId: string) {
  return apiFetch<{
    required: string[]
    recommended: string[]
    excludedDirs: string[]
    storeOnly: string[]
    displayOnly: string[]
    featureMatrix: Record<string, { needs: string[]; not_needed: string[] }>
    limits: Record<string, number>
  }>(`/api/repos/${repoId}/knowledge/policy`)
}

export function analyzeIssue(repoId: string, issue: GithubIssue) {
  return apiFetch<IssueAnalysis>('/api/issues/analyze', {
    method: 'POST',
    body: JSON.stringify({ repoId, issue }),
  })
}

export function fetchIssueAnalysis(issueId: string) {
  return apiFetch<IssueAnalysis>(`/api/issues/${issueId}/analysis`)
}

export function buildKnowledge(
  repoId: string,
  options?: { indexEachCommit?: boolean; maxCommits?: number; commitShas?: string[] },
) {
  return apiFetch<{
    repoId: string
    indexedCommits: number
    commits: { commitSha: string; shortSha: string; message: string }[]
    activeCommitSha: string
    deduplication: KnowledgeOverview['deduplication']
    status: string
  }>(`/api/repos/${repoId}/knowledge/build`, {
    method: 'POST',
    body: JSON.stringify({
      indexEachCommit: options?.indexEachCommit ?? false,
      maxCommits: options?.maxCommits ?? 30,
      commitShas: options?.commitShas,
    }),
  })
}

export function fetchKnowledge(repoId: string, commitSha?: string) {
  const q = commitSha ? `?commit=${encodeURIComponent(commitSha)}` : ''
  return apiFetch<KnowledgeOverview>(`/api/repos/${repoId}/knowledge${q}`)
}

export function fetchKnowledgeCommits(repoId: string) {
  return apiFetch<{ items: IndexedCommit[] }>(`/api/repos/${repoId}/knowledge/commits`)
}

export function compareKnowledgeCommits(repoId: string, base: string, head: string) {
  return apiFetch<CommitCompareResult>(
    `/api/repos/${repoId}/knowledge/compare?base=${encodeURIComponent(base)}&head=${encodeURIComponent(head)}`,
  )
}

export function updateKnowledgeSettings(repoId: string, settings: Partial<KnowledgeSettings>) {
  return apiFetch<KnowledgeSettings>(`/api/repos/${repoId}/knowledge/settings`, {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

export function sendChatMessage(repoId: string, message: string) {
  return apiFetch<ChatResponse>('/api/chat', {
    method: 'POST',
    body: JSON.stringify({ repoId, message }),
  })
}

export function fetchLlmConfig() {
  return apiFetch<{
    configured: boolean
    model: string
    baseUrl?: string
    provider?: string
  }>('/api/config/llm')
}

export function fetchBackendHealth() {
  return apiFetch<{
    pid: number
    startedAt: string
    llmConfigured: boolean
    llmModel: string
    llmProvider: string
  }>('/api/health')
}

export function fetchPortfolioOverview() {
  return apiFetch<PortfolioOverview>('/api/portfolio/overview')
}

export function executeGitAction(repoId: string, action: string, params: Record<string, string> = {}) {
  return apiFetch<Record<string, unknown>>(`/api/repos/${repoId}/actions`, {
    method: 'POST',
    body: JSON.stringify({ action, params }),
  })
}

export function executeNlCommand(repoId: string, command: string) {
  return apiFetch<{ success: boolean; message: string; action?: string; result?: unknown }>(
    `/api/repos/${repoId}/actions/nl`,
    { method: 'POST', body: JSON.stringify({ command }) },
  )
}
