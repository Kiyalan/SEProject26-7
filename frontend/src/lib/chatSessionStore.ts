import type { ChatMessage } from './FrontendTypes'

export type ChatSessionState = {
  messages: ChatMessage[]
  loading: boolean
  statusMessage: string | null
  lastFailedQuestion: string | null
  input: string
  requestSeq: number
}

type Listener = () => void

type Session = {
  messages: ChatMessage[]
  loading: boolean
  statusMessage: string | null
  lastFailedQuestion: string | null
  input: string
  abort: AbortController | null
  requestSeq: number
  listeners: Set<Listener>
  /** Stable snapshot for useSyncExternalStore — must keep referential equality until mutate. */
  snapshot: ChatSessionState
}

const STORAGE_PREFIX = 'repopilot-chat:'
const sessions = new Map<string, Session>()

const EMPTY_SNAPSHOT: ChatSessionState = {
  messages: [],
  loading: false,
  statusMessage: null,
  lastFailedQuestion: null,
  input: '',
  requestSeq: 0,
}

function storageKey(repoId: string) {
  return `${STORAGE_PREFIX}${repoId}`
}

function loadPersisted(repoId: string): ChatMessage[] {
  try {
    const raw = sessionStorage.getItem(storageKey(repoId))
    if (!raw) return []
    const parsed = JSON.parse(raw) as ChatMessage[]
    return Array.isArray(parsed)
      ? parsed.map((m) => ({ ...m, streaming: false }))
      : []
  } catch {
    return []
  }
}

function persist(repoId: string, messages: ChatMessage[]) {
  try {
    const toSave = messages.map((m) => ({ ...m, streaming: false }))
    sessionStorage.setItem(storageKey(repoId), JSON.stringify(toSave))
  } catch {
    // quota / private mode — ignore
  }
}

function refreshSnapshot(session: Session) {
  session.snapshot = {
    messages: session.messages,
    loading: session.loading,
    statusMessage: session.statusMessage,
    lastFailedQuestion: session.lastFailedQuestion,
    input: session.input,
    requestSeq: session.requestSeq,
  }
}

function ensure(repoId: string): Session {
  let session = sessions.get(repoId)
  if (!session) {
    const messages = repoId === '__none__' ? [] : loadPersisted(repoId)
    session = {
      messages,
      loading: false,
      statusMessage: null,
      lastFailedQuestion: null,
      input: '',
      abort: null,
      requestSeq: 0,
      listeners: new Set(),
      snapshot: EMPTY_SNAPSHOT,
    }
    refreshSnapshot(session)
    sessions.set(repoId, session)
  }
  return session
}

function notify(session: Session) {
  refreshSnapshot(session)
  session.listeners.forEach((fn) => fn())
}

function getOrHydrateSession(repoId: string): Session {
  const session = ensure(repoId)
  if (
    repoId !== '__none__' &&
    session.messages.length === 0 &&
    session.input === '' &&
    !session.loading &&
    session.statusMessage === null
  ) {
    const persisted = loadPersisted(repoId)
    if (persisted.length > 0) {
      session.messages = persisted
      refreshSnapshot(session)
    }
  }
  return session
}

export function getChatSession(repoId: string): ChatSessionState {
  return getOrHydrateSession(repoId).snapshot
}

export function subscribeChatSession(repoId: string, listener: Listener): () => void {
  const s = ensure(repoId)
  s.listeners.add(listener)
  return () => {
    s.listeners.delete(listener)
  }
}

export function patchChatSession(repoId: string, patch: Partial<ChatSessionState>) {
  const s = ensure(repoId)
  if (patch.messages !== undefined) {
    s.messages = patch.messages
    persist(repoId, s.messages)
  }
  if (patch.loading !== undefined) s.loading = patch.loading
  if (patch.statusMessage !== undefined) s.statusMessage = patch.statusMessage
  if (patch.lastFailedQuestion !== undefined) s.lastFailedQuestion = patch.lastFailedQuestion
  if (patch.input !== undefined) s.input = patch.input
  notify(s)
}

export function updateChatMessages(
  repoId: string,
  updater: (prev: ChatMessage[]) => ChatMessage[],
) {
  const s = ensure(repoId)
  s.messages = updater(s.messages)
  persist(repoId, s.messages)
  notify(s)
}

export function beginChatRequest(repoId: string): { seq: number; controller: AbortController } {
  const s = ensure(repoId)
  s.abort?.abort()
  const controller = new AbortController()
  s.abort = controller
  s.requestSeq += 1
  s.loading = true
  s.statusMessage = '正在连接…'
  s.lastFailedQuestion = null
  notify(s)
  return { seq: s.requestSeq, controller }
}

export function isCurrentChatRequest(repoId: string, seq: number) {
  return ensure(repoId).requestSeq === seq
}

export function endChatRequest(repoId: string, seq: number, failedQuestion?: string | null) {
  const s = ensure(repoId)
  if (s.requestSeq !== seq) return
  s.loading = false
  s.statusMessage = null
  s.abort = null
  if (failedQuestion !== undefined) {
    s.lastFailedQuestion = failedQuestion
  }
  s.messages = s.messages.map((m) => ({ ...m, streaming: false }))
  persist(repoId, s.messages)
  notify(s)
}

export function abortChatRequest(repoId: string) {
  const s = ensure(repoId)
  s.abort?.abort()
  s.abort = null
  s.loading = false
  s.statusMessage = null
  s.messages = s.messages.map((m) => ({ ...m, streaming: false }))
  persist(repoId, s.messages)
  notify(s)
}

export function clearChatSession(repoId: string) {
  abortChatRequest(repoId)
  const s = ensure(repoId)
  s.messages = []
  s.input = ''
  s.lastFailedQuestion = null
  s.requestSeq += 1
  try {
    sessionStorage.removeItem(storageKey(repoId))
  } catch {
    // ignore
  }
  notify(s)
}
