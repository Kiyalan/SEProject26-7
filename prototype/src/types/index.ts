export type IssueType =
  | 'usage_question'
  | 'duplicate'
  | 'insufficient_info'
  | 'bug_fix'
  | 'feature_request'

export interface Repository {
  id: string
  name: string
  fullName: string
  description: string
  stars: number
  openIssues: number
  language: string
  lastSync: string
  syncStatus: 'synced' | 'syncing' | 'error'
  htmlUrl?: string
  private?: boolean
  defaultBranch?: string
}

export interface Issue {
  id: string
  repoId: string
  number: number
  title: string
  body: string
  author: string
  createdAt: string
  labels: string[]
  type: IssueType
  aiSummary: string
  suggestedReply: string
  confidence: number
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: { file: string; line?: number }[]
  questionType?: 'what' | 'where' | 'how'
}

export interface Conversation {
  id: string
  repoId: string
  title: string
  createdAt: string
  updatedAt: string
  msgCount: number
}

export interface ConversationMessage {
  id: string
  conversationId: string
  role: 'user' | 'assistant'
  content: string
  questionType?: string
  citations?: { file: string; line?: number }[]
  createdAt: string
}

export interface KnowledgeNode {
  key: string
  title: string
  type: 'folder' | 'file' | 'module'
  children?: KnowledgeNode[]
}
