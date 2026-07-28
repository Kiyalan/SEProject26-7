import { projectDisplayName } from '../config/BaseConfig'
import { Alert, Input, Spin, message as antdMessage } from 'antd'
import { PaperAirplaneIcon, PersonIcon, SyncIcon, BookmarkIcon } from '@primer/octicons-react'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import { fetchKnowledge, fetchLlmConfig } from '../api/generated'
import { getToken } from '../lib/AuthAxios'
import type { ChatMessage } from '../lib/FrontendTypes'
import {
  beginChatRequest,
  endChatRequest,
  getChatSession,
  isCurrentChatRequest,
  patchChatSession,
  subscribeChatSession,
  updateChatMessages,
} from '../lib/chatSessionStore'

const questionTypeMap: Record<string, { label: string; className: string }> = {
  what: { label: 'What', className: 'gh-label gh-label-blue' },
  where: { label: 'Where', className: 'gh-label' },
  how: { label: 'How', className: 'gh-label gh-label-green' },
}

const intentLabels: Record<string, string> = {
  code: '代码',
  history: '历史',
  api: '接口',
  deployment: '部署',
  overview: '概览',
  branches: '分支',
  portfolio: '多仓库',
}

function formatIntent(intent?: string) {
  if (!intent) return null
  return intent
    .split('+')
    .filter(Boolean)
    .map((part) => intentLabels[part] || part)
    .join(' · ')
}

const MAX_QUESTION_LENGTH = 2000

function useChatSession(repoId: string) {
  const id = repoId || '__none__'
  return useSyncExternalStore(
    (onStoreChange) => subscribeChatSession(id, onStoreChange),
    () => getChatSession(id),
    () => getChatSession(id),
  )
}

function parseSseData(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

function errorText(data: unknown): string {
  if (typeof data === 'string') return data
  if (data && typeof data === 'object' && 'message' in data) {
    return String((data as { message: unknown }).message)
  }
  return '问答失败'
}

export default function Chat() {
  const { currentRepoId, setCurrentRepo, repoList } = useRepoContext()
  const session = useChatSession(currentRepoId || '__none__')
  const [llmEnabled, setLlmEnabled] = useState(false)
  const [knowledgeReady, setKnowledgeReady] = useState(false)
  const [savingFaqId, setSavingFaqId] = useState<string | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetchLlmConfig()
      .then(({ data: cfg }) => setLlmEnabled(Boolean(cfg.apiKey?.trim())))
      .catch(() => setLlmEnabled(false))
  }, [])

  useEffect(() => {
    if (!currentRepoId) {
      setKnowledgeReady(false)
      return
    }
    fetchKnowledge({ path: { repoId: currentRepoId } })
      .then(({ data }) => {
        const chunks = Number(data.chunkCount || 0)
        const nodes = Number((data as { graphStatus?: { nodeCount?: number } }).graphStatus?.nodeCount || 0)
        setKnowledgeReady(data.status === 'ready' && (chunks > 0 || nodes > 0))
      })
      .catch(() => setKnowledgeReady(false))
  }, [currentRepoId])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [session.messages, session.loading, session.statusMessage])

  const ensureAssistantBubble = (assistId: string, patch: Partial<ChatMessage> = {}) => {
    if (!currentRepoId) return
    updateChatMessages(currentRepoId, (prev) => {
      if (prev.some((m) => m.id === assistId)) {
        return prev.map((m) => (m.id === assistId ? { ...m, ...patch } : m))
      }
      return [
        ...prev,
        {
          id: assistId,
          role: 'assistant',
          content: '',
          streaming: true,
          error: false,
          ...patch,
        },
      ]
    })
  }

  const askStream = async (question: string) => {
    const trimmed = question.trim()
    if (!trimmed || !currentRepoId || session.loading) return
    if (trimmed.length > MAX_QUESTION_LENGTH) {
      updateChatMessages(currentRepoId, (prev) => [
        ...prev,
        {
          id: `a-${Date.now()}`,
          role: 'assistant',
          content: `问题过长（最多 ${MAX_QUESTION_LENGTH} 字），请精简后重试`,
          error: true,
        },
      ])
      return
    }

    const { seq, controller } = beginChatRequest(currentRepoId)
    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: trimmed,
    }
    updateChatMessages(currentRepoId, (prev) => [...prev, userMsg])

    const token = getToken()
    const assistId = `a-${Date.now()}`
    let accumulated = ''
    let sawError = false
    let gotDone = false

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token ?? ''}`,
        },
        body: JSON.stringify({ repoId: currentRepoId, message: trimmed }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const errBody = await response.text().catch(() => '问答失败')
        if (!isCurrentChatRequest(currentRepoId, seq)) return
        ensureAssistantBubble(assistId, { content: errBody, error: true, streaming: false })
        endChatRequest(currentRepoId, seq, trimmed)
        return
      }

      const reader = response.body?.getReader()
      if (!reader) {
        if (!isCurrentChatRequest(currentRepoId, seq)) return
        ensureAssistantBubble(assistId, { content: '无法读取流式响应', error: true, streaming: false })
        endChatRequest(currentRepoId, seq, trimmed)
        return
      }

      // Show bubble immediately so status/tokens have a target even before meta
      ensureAssistantBubble(assistId, { content: '' })

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (!isCurrentChatRequest(currentRepoId, seq)) return
        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        let currentEvent = ''
        for (const line of lines) {
          const trimmedLine = line.trim()
          if (trimmedLine.startsWith('event:')) {
            currentEvent = trimmedLine.slice(6).trim()
            continue
          }
          if (trimmedLine.startsWith('data:')) {
            const raw = trimmedLine.slice(5).trim()
            const event = parseSseData(raw)

            if (currentEvent === 'status') {
              const msg =
                event && typeof event === 'object' && 'message' in event
                  ? String((event as { message: unknown }).message)
                  : typeof event === 'string'
                    ? event
                    : '处理中…'
              patchChatSession(currentRepoId, { statusMessage: msg })
            } else if (currentEvent === 'meta') {
              const meta = (event && typeof event === 'object' ? event : {}) as {
                questionType?: string
                citations?: ChatMessage['citations']
                intent?: string
              }
              ensureAssistantBubble(assistId, {
                questionType: meta.questionType as ChatMessage['questionType'],
                citations: meta.citations,
                intent: meta.intent,
                streaming: true,
                error: false,
              })
            } else if (currentEvent === 'token') {
              const piece =
                event && typeof event === 'object' && 'content' in event
                  ? String((event as { content: unknown }).content)
                  : ''
              if (piece) {
                accumulated += piece
                ensureAssistantBubble(assistId, {
                  content: accumulated,
                  streaming: true,
                  error: false,
                })
              }
            } else if (currentEvent === 'done') {
              gotDone = true
              const finalContent =
                event && typeof event === 'object' && 'answer' in event
                  ? String((event as { answer: unknown }).answer || accumulated)
                  : accumulated
              accumulated = finalContent
              ensureAssistantBubble(assistId, {
                content: finalContent,
                streaming: false,
                error: false,
              })
            } else if (currentEvent === 'error') {
              sawError = true
              ensureAssistantBubble(assistId, {
                content: errorText(event),
                error: true,
                streaming: false,
              })
            } else if (currentEvent === 'data') {
              const payload = (event && typeof event === 'object' ? event : {}) as {
                content?: string
                questionType?: string
                citations?: ChatMessage['citations']
                intent?: string
              }
              accumulated = payload.content ?? accumulated
              ensureAssistantBubble(assistId, {
                content: accumulated,
                streaming: false,
                questionType: payload.questionType as ChatMessage['questionType'],
                citations: payload.citations,
                intent: payload.intent,
              })
            }
            currentEvent = ''
          }
        }
      }

      if (!isCurrentChatRequest(currentRepoId, seq)) return

      if (!sawError && !accumulated && !gotDone) {
        ensureAssistantBubble(assistId, {
          content: '未收到有效回答。若长时间无响应，请检查 LLM 配置与 CodeWiki 是否可用。',
          error: true,
          streaming: false,
        })
        endChatRequest(currentRepoId, seq, trimmed)
        return
      }

      endChatRequest(currentRepoId, seq, sawError ? trimmed : null)
    } catch (err) {
      if (!isCurrentChatRequest(currentRepoId, seq)) return
      if ((err as Error).name === 'AbortError') {
        endChatRequest(currentRepoId, seq, null)
        return
      }
      ensureAssistantBubble(assistId, {
        content: err instanceof Error ? err.message : '流式问答失败',
        error: true,
        streaming: false,
      })
      endChatRequest(currentRepoId, seq, trimmed)
    }
  }

  const handleSend = async () => {
    if (!currentRepoId) return
    const question = session.input.trim()
    if (!question) return
    patchChatSession(currentRepoId, { input: '' })
    await askStream(question)
  }

  const handleRetry = async () => {
    if (!session.lastFailedQuestion) return
    await askStream(session.lastFailedQuestion)
  }

  const addToFaq = async (assistantMsg: ChatMessage) => {
    if (!currentRepoId || !assistantMsg.content.trim() || assistantMsg.error) return
    const msgs = session.messages
    const idx = msgs.findIndex((m) => m.id === assistantMsg.id)
    let question = ''
    for (let i = idx - 1; i >= 0; i -= 1) {
      if (msgs[i].role === 'user') {
        question = msgs[i].content
        break
      }
    }
    if (!question) {
      antdMessage.warning('找不到对应的用户问题')
      return
    }
    setSavingFaqId(assistantMsg.id)
    try {
      const token = getToken()
      const res = await fetch(`/api/repos/${encodeURIComponent(currentRepoId)}/faq/items`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token ?? ''}`,
        },
        body: JSON.stringify({
          question,
          answer: assistantMsg.content,
          category: 'chat',
        }),
      })
      if (!res.ok) {
        const text = await res.text().catch(() => '加入 FAQ 失败')
        throw new Error(text)
      }
      antdMessage.success('已加入当前仓库 FAQ')
    } catch (err) {
      antdMessage.error(err instanceof Error ? err.message : '加入 FAQ 失败')
    } finally {
      setSavingFaqId(null)
    }
  }

  const { messages, loading, statusMessage, lastFailedQuestion, input } = session

  return (
    <PageShell
      title="智能问答"
      description={
        llmEnabled
          ? '基于 GraphRAG 检索 + LLM 流式生成；对话会保留在本会话，切页不丢失'
          : '检索摘要模式（配置 LLM_API_KEY 可启用大模型）'
      }
      actions={
        <select
          className="gh-btn"
          value={currentRepoId}
          onChange={(e) => setCurrentRepo(e.target.value)}
          style={{ minWidth: 220 }}
        >
          {repoList.map((r) => (
            <option key={r.id} value={r.id}>
              {r.fullName}
            </option>
          ))}
        </select>
      }
    >
      {!knowledgeReady && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="当前仓库尚未构建知识库"
          description="请先在「知识库」页面构建索引，或使用右侧「同步知识库」快捷操作。"
        />
      )}

      <div className="gh-box" style={{ display: 'flex', flexDirection: 'column', minHeight: 520 }}>
        <div className="gh-box-body" style={{ flex: 1, overflowY: 'auto', maxHeight: 440 }}>
          {messages.length === 0 && !loading ? (
            <p className="gh-muted" style={{ margin: 0 }}>
              试试：「这个项目是做什么的？」「路由配置在哪里？」「如何启动项目？」
            </p>
          ) : (
            messages.map((item) => (
              <div
                key={item.id}
                style={{
                  display: 'flex',
                  gap: 12,
                  marginBottom: 16,
                  paddingBottom: 16,
                  borderBottom: '1px solid var(--gh-border-muted)',
                }}
              >
                <div
                  style={{
                    width: 32,
                    height: 32,
                    borderRadius: '50%',
                    background: item.role === 'user' ? '#ddf4ff' : item.error ? '#ffebe9' : '#dafbe1',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0,
                  }}
                >
                  {item.role === 'user' ? <PersonIcon size={16} /> : <span>🤖</span>}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4, flexWrap: 'wrap' }}>
                    <strong>{item.role === 'user' ? '你' : projectDisplayName}</strong>
                    {item.questionType && (
                      <span className={questionTypeMap[item.questionType]?.className ?? 'gh-label'}>
                        {questionTypeMap[item.questionType]?.label ?? item.questionType}
                      </span>
                    )}
                    {item.intent && (
                      <span className="gh-label rp-intent">意图 · {formatIntent(item.intent)}</span>
                    )}
                    {item.emptyEvidence && <span className="gh-label gh-label-orange">无证据</span>}
                    {item.error && <span className="gh-label gh-label-red">失败</span>}
                    {item.streaming && (
                      <span className="gh-label gh-label-blue" style={{ animation: 'pulse 1s infinite' }}>
                        ● 生成中
                      </span>
                    )}
                  </div>
                  <p style={{ margin: 0, whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{item.content}</p>
                  {item.citations?.map((c) => (
                    <div key={`${c.file}-${c.line}`} className="gh-muted" style={{ fontSize: 12, marginTop: 4 }}>
                      引用：{c.file}
                      {c.line ? `:${c.line}` : ''}
                    </div>
                  ))}
                  {item.role === 'assistant' && !item.error && !item.streaming && item.content.trim() && (
                    <div style={{ marginTop: 8 }}>
                      <button
                        type="button"
                        className="gh-btn gh-btn-sm"
                        disabled={savingFaqId === item.id}
                        onClick={() => addToFaq(item)}
                        title="将本轮问答写入当前仓库 FAQ"
                      >
                        <BookmarkIcon size={12} />
                        {savingFaqId === item.id ? '保存中…' : '加入 FAQ'}
                      </button>
                    </div>
                  )}
                </div>
              </div>
            ))
          )}

          {loading && (
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 8 }}>
              <Spin size="small" />
              <div>
                <div className="rp-loading-pulse" style={{ fontWeight: 600 }}>
                  {statusMessage || `${projectDisplayName} 正在检索并回答…`}
                </div>
                <div className="rp-typing" aria-hidden>
                  <span />
                  <span />
                  <span />
                </div>
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
        <div style={{ padding: '12px 16px', borderTop: '1px solid var(--gh-border-muted)' }}>
          {lastFailedQuestion && !loading && (
            <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'flex-end' }}>
              <button type="button" className="gh-btn gh-btn-sm" onClick={handleRetry}>
                <SyncIcon size={12} />
                重试上一问
              </button>
            </div>
          )}
          <div style={{ display: 'flex', gap: 8 }}>
            <Input
              size="large"
              placeholder="例如：路由配置在哪里？如何运行测试？"
              value={input}
              maxLength={MAX_QUESTION_LENGTH}
              onChange={(e) => {
                if (currentRepoId) patchChatSession(currentRepoId, { input: e.target.value })
              }}
              onPressEnter={handleSend}
              disabled={loading}
            />
            <button
              type="button"
              className="gh-btn gh-btn-primary"
              onClick={handleSend}
              disabled={loading || !currentRepoId || !input.trim()}
              style={{ height: 40, padding: '0 16px' }}
            >
              <PaperAirplaneIcon size={16} />
              {loading ? '…' : '发送'}
            </button>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
