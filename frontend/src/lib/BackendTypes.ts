import type { KnowledgeNode } from './FrontendTypes'

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

export interface UserProfile {
  login: string
  name: string | null
  avatarUrl: string
}

export interface LlmConfig {
  baseUrl: string
  apiKey: string
  model: string
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
