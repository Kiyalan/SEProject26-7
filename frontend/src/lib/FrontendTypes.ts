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

export type RepositoryList = Repository[]

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: { file: string; line?: number }[]
  questionType?: 'what' | 'where' | 'how'
}

export interface KnowledgeNode {
  key: string
  title: string
  type: 'folder' | 'file' | 'module'
  children?: KnowledgeNode[]
}
