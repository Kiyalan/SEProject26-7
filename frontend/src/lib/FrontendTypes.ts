import type { Repository } from '../api/generated'

export type { Repository, KnowledgeNode } from '../api/generated'

export type RepositoryList = Repository[]

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: { file: string; line?: number }[]
  questionType?: 'what' | 'where' | 'how'
  intent?: string
  emptyEvidence?: boolean
  error?: boolean
  streaming?: boolean
}
