import { getToken, clearAuth } from './auth'
import type { KnowledgeNode, Repository } from '../types'

const API_BASE = import.meta.env.VITE_API_URL || ''

export interface GithubIssue {
  id: string
  repoId: string
  number: number
  title: string
  body: string
  state?: string
  author: string
  createdAt: string
  updatedAt?: string
  labels: string[]
  htmlUrl: string
  comments?: number
}

export interface GithubUser {
  login: string
  name: string | null
  avatarUrl: string
}

export interface IndexedCommit {
  commitSha: string
  shortSha: string
  parentSha?: string
  message: string
  author: string
  committedAt: string
  indexedAt: string
  fileCount: number
  chunkCount: number
  status: string
}

export interface KnowledgeSettings {
  indexEachCommit: boolean
  maxCommits: number
  activeCommitSha: string
}

export interface CommitCompareResult {
  baseSha: string
  headSha: string
  baseMessage: string
  headMessage: string
  added: string[]
  removed: string[]
  modified: string[]
  unchanged: number
  sharedBlobCount: number
  previews: { path: string; diff: string }[]
}

export interface KnowledgeOverview {
  repoId: string
  fullName?: string
  status: 'not_indexed' | 'indexing' | 'ready' | 'idle'
  indexedAt?: string
  fileCount: number
  chunkCount: number
  tree: KnowledgeNode[]
  modules: { name: string; desc: string; files: number; deps: string[] }[]
  dependencies: string[]
  summary?: string
  languages?: Record<string, number>
  readmePath?: string
  readmePreview?: string
  commitSha?: string
  shortSha?: string
  topics?: string[]
  license?: string
  indexedFiles?: { path: string; size: number; language: string }[]
  commits?: IndexedCommit[]
  settings?: KnowledgeSettings
  deduplication?: {
    indexedCommits: number
    uniqueFileBlobs: number
    uniqueChunkBlobs: number
    totalBlobBytes: number
    fileReferences: number
  }
  storageModel?: {
    displayed: string[]
    databaseOnly: string[]
    dedupStrategy?: string
  }
}

export interface ChatResponse {
  answer: string
  questionType: 'what' | 'where' | 'how'
  citations: { file: string; line?: number }[]
  llmEnabled: boolean
}

export interface IssueAnalysis {
  issueId: string
  repoId: string
  number: number
  title: string
  type:
    | 'usage_question'
    | 'duplicate'
    | 'insufficient_info'
    | 'bug_fix'
    | 'feature_request'
    | 'other'
  typeLabel: string
  confidence: number
  summary: string
  suggestedReply: string
  reason: string
  relatedFiles: { file: string; line?: number }[]
  analyzedAt: string
  needsCodeChange: boolean
  llmEnhanced?: boolean
}

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

/** 带 LLM 增强的分析（额外一次 LLM 调用） */
export function analyzeIssueWithLlm(repoId: string, issue: GithubIssue) {
  return apiFetch<IssueAnalysis>('/api/issues/analyze', {
    method: 'POST',
    body: JSON.stringify({ repoId, issue, useLlm: true }),
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
  return apiFetch<ChatResponse>('/api/chat/sync', {
    method: 'POST',
    body: JSON.stringify({ repoId, message }),
  })
}

/**
 * 流式问答（SSE）。
 *
 * 通过 fetch + ReadableStream 逐行读取 SSE 事件，
 * 每收到一个 chunk 调用 onChunk，完成或出错时 resolve/reject。
 *
 * onChunk 参数：
 * - { type: "header", questionType, citations }  首帧元数据
 * - { type: "content", content }                 文本增量
 * - { type: "error", content }                   错误信息
 */
export function sendChatMessageStream(
  repoId: string,
  message: string,
  commitSha: string | undefined,
  conversationId: string | undefined,
  onChunk: (data: { type: string; questionType?: string; citations?: { file: string; line?: number }[]; content?: string; conversationId?: string }) => void,
  signal?: AbortSignal,
): Promise<string> {
  const token = getToken()
  return fetch(`${API_BASE}/api/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ repoId, message, commit: commitSha || undefined, conversationId }),
    signal,
  }).then(async (response) => {
    if (response.status === 401) {
      clearAuth()
      window.location.href = '/login'
      throw new Error('未登录')
    }

    if (!response.ok) {
      const text = await response.text()
      let msg = `请求失败: ${response.status}`
      try {
        const data = JSON.parse(text) as { detail?: string }
        if (data.detail) msg = data.detail
      } catch {
        if (text) msg = text
      }
      throw new Error(msg)
    }

    const contentType = response.headers.get('content-type') || ''
    if (contentType.includes('application/json')) {
      const data = await response.json() as ChatResponse & { conversationId?: string }
      onChunk({
        type: 'header',
        questionType: data.questionType,
        citations: data.citations,
        conversationId: data.conversationId,
      })
      onChunk({ type: 'content', content: data.answer })
      return data.conversationId || ''
    }

    // SSE stream
    const reader = response.body?.getReader()
    if (!reader) throw new Error('不支持流式读取')

    const decoder = new TextDecoder()
    let buffer = ''
    let resolvedConvId = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed || !trimmed.startsWith('data: ')) continue
        const dataStr = trimmed.slice(6)
        if (dataStr === '[DONE]') return resolvedConvId
        try {
          const parsed = JSON.parse(dataStr) as {
            type: string
            questionType?: string
            citations?: { file: string; line?: number }[]
            content?: string
            conversationId?: string
          }
          if (parsed.conversationId) resolvedConvId = parsed.conversationId
          onChunk(parsed)
        } catch {
          // 忽略解析失败的行
        }
      }
    }

    // 处理剩余缓冲
    if (buffer.trim()) {
      const trimmed = buffer.trim()
      if (trimmed.startsWith('data: ')) {
        const dataStr = trimmed.slice(6)
        if (dataStr !== '[DONE]') {
          try {
            const parsed = JSON.parse(dataStr) as { type: string; content?: string }
            onChunk(parsed)
          } catch {
            // ignore
          }
        }
      }
    }
    return resolvedConvId
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

export interface PortfolioOverview {
  summary: {
    repoCount: number
    indexedCount: number
    indexRate: number
    totalStars: number
    totalOpenIssues: number
    totalIndexedFiles: number
    totalChunks: number
  }
  languageBreakdown: { language: string; count: number; percent: number }[]
  clusters: Record<string, string[]>
  timeline: { fullName: string; pushedAt: string; indexedAt: string }[]
  repos: {
    repoId: string
    fullName: string
    language: string
    stars: number
    openIssues: number
    pushedAt: string
    knowledge: {
      indexed: boolean
      indexedAt: string
      fileCount: number
      chunkCount: number
      commitSnapshots: number
    }
  }[]
  notes: string[]
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

// ── 对话历史 ──

export interface ConversationSummary {
  id: string
  repoId: string
  title: string
  createdAt: string
  updatedAt: string
  msgCount: number
}

export interface ConvMessage {
  id: string
  conversationId: string
  role: 'user' | 'assistant'
  content: string
  questionType?: string
  citations?: { file: string; line?: number }[]
  createdAt: string
}

export function fetchConversations(repoId: string) {
  return apiFetch<{ items: ConversationSummary[] }>(
    `/api/conversations?repoId=${encodeURIComponent(repoId)}`,
  )
}

export function fetchConversationMessages(conversationId: string) {
  return apiFetch<{ items: ConvMessage[] }>(
    `/api/conversations/${conversationId}/messages`,
  )
}

export function deleteConversation(conversationId: string) {
  return apiFetch<{ ok: boolean }>(`/api/conversations/${conversationId}`, {
    method: 'DELETE',
  })
}
