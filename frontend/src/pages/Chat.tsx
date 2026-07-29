import { projectDisplayName } from '../config/BaseConfig'
import { Alert, Button, Card, Input, Select, Space, Spin, Tag, Typography, message as antdMessage } from 'antd'
import {
  BookOutlined,
  SendOutlined,
  SyncOutlined,
  UserOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import { fetchKnowledge, fetchLlmConfig } from '../api/generated'
import { authAxios, getToken } from '../lib/AuthAxios'
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

const { Text, Title, Paragraph } = Typography

const SAMPLE_QUESTIONS = [
  '这个项目是做什么的？',
  '路由配置在哪里？',
  '如何启动项目？',
]

const cardStyle = {
  borderRadius: 12,
  boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
  border: '1px solid #e5e7eb',
} as const

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
  knowledge_status: '知识库状态',
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
  const [knowledgeMeta, setKnowledgeMeta] = useState<{
    status: string
    fileCount: number
    chunkCount: number
    nodeCount: number
  } | null>(null)
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
      setKnowledgeMeta(null)
      return
    }
    fetchKnowledge({ path: { repoId: currentRepoId } })
      .then(({ data }) => {
        const chunks = Number(data.chunkCount || 0)
        const files = Number(data.fileCount || 0)
        const graph = (data as { graphStatus?: { nodeCount?: number } }).graphStatus
        const nodes = Number(graph?.nodeCount || 0)
        const status = String(data.status || 'not_indexed')
        const ready = status === 'ready' && (chunks > 0 || nodes > 0 || files > 0)
        setKnowledgeReady(ready)
        setKnowledgeMeta({
          status,
          fileCount: files,
          chunkCount: chunks,
          nodeCount: nodes,
        })
      })
      .catch(() => {
        setKnowledgeReady(false)
        setKnowledgeMeta(null)
      })
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
      await authAxios.post(`/api/repos/${encodeURIComponent(currentRepoId)}/faq/items`, {
        question,
        answer: assistantMsg.content,
        category: 'chat',
      })
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
        <Select
          value={currentRepoId || undefined}
          onChange={(value) => setCurrentRepo(value)}
          style={{ minWidth: 240 }}
          placeholder="选择仓库"
          options={repoList.map((r) => ({ value: r.id, label: r.fullName }))}
        />
      }
    >
      <div className="chat-page">
        {currentRepoId && (
          knowledgeReady ? (
            <Alert
              type="success"
              showIcon
              style={{ borderRadius: 10 }}
              message="知识库已构建"
              description={
                knowledgeMeta
                  ? `状态 ${knowledgeMeta.status} · 文件 ${knowledgeMeta.fileCount} · 片段 ${knowledgeMeta.chunkCount} · 图节点 ${knowledgeMeta.nodeCount}`
                  : '当前仓库索引可用，可直接提问。'
              }
            />
          ) : (
            <Alert
              type="warning"
              showIcon
              icon={<WarningOutlined />}
              style={{ borderRadius: 10 }}
              message="当前仓库尚未构建知识库"
              description="请先在「知识库」页面构建索引，或使用右侧「同步知识库」快捷操作。"
            />
          )
        )}

        <Card
          style={{ ...cardStyle, display: 'flex', flexDirection: 'column', height: 'calc(100vh - 220px)' }}
          styles={{ body: { padding: 0, display: 'flex', flexDirection: 'column', height: '100%' } }}
        >
          <div style={{ flex: 1, overflowY: 'auto', minHeight: 0, padding: '24px 28px' }}>
            {messages.length === 0 && !loading ? (
              <div style={{ textAlign: 'center', padding: '48px 0' }}>
                <Title level={5} style={{ marginBottom: 16, color: '#111827' }}>
                  试试问这些问题
                </Title>
                <Space wrap size={10} style={{ justifyContent: 'center' }}>
                  {SAMPLE_QUESTIONS.map((q) => (
                    <Tag
                      key={q}
                      color="blue"
                      style={{ borderRadius: 10, padding: '6px 12px', fontSize: 13, cursor: 'pointer' }}
                      onClick={() => {
                        if (currentRepoId) patchChatSession(currentRepoId, { input: q })
                      }}
                    >
                      {q}
                    </Tag>
                  ))}
                </Space>
              </div>
            ) : (
              messages.map((item) => (
                <div
                  key={item.id}
                  style={{
                    display: 'flex',
                    gap: 12,
                    marginBottom: 16,
                    paddingBottom: 16,
                    borderBottom: '1px solid #f0f2f5',
                  }}
                >
                  <div
                    style={{
                      width: 36,
                      height: 36,
                      borderRadius: '50%',
                      background: item.role === 'user' ? '#E8F3FF' : item.error ? '#FFECE8' : '#E8FFEA',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                      color: item.role === 'user' ? '#165DFF' : item.error ? '#F53F3F' : '#00B42A',
                    }}
                  >
                    {item.role === 'user' ? <UserOutlined /> : <span>🤖</span>}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
                      <Text strong>{item.role === 'user' ? '你' : projectDisplayName}</Text>
                      {item.questionType && (
                        <Tag style={{ borderRadius: 8 }}>
                          {questionTypeMap[item.questionType]?.label ?? item.questionType}
                        </Tag>
                      )}
                      {item.intent && (
                        <Tag color="processing" style={{ borderRadius: 8 }}>
                          意图 · {formatIntent(item.intent)}
                        </Tag>
                      )}
                      {item.emptyEvidence && (
                        <Tag color="orange" style={{ borderRadius: 8 }}>
                          无证据
                        </Tag>
                      )}
                      {item.error && (
                        <Tag color="error" style={{ borderRadius: 8 }}>
                          失败
                        </Tag>
                      )}
                      {item.streaming && (
                        <Tag color="blue" style={{ borderRadius: 8 }}>
                          ● 生成中
                        </Tag>
                      )}
                    </div>
                    <Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                      {item.content.replace(/\*\*/g, '')}
                    </Paragraph>
                    {item.citations?.map((c) => (
                      <Text key={`${c.file}-${c.line}`} type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 4 }}>
                        引用：{c.file}
                        {c.line ? `:${c.line}` : ''}
                      </Text>
                    ))}
                    {item.role === 'assistant' && !item.error && !item.streaming && item.content.trim() && (
                      <div style={{ marginTop: 10 }}>
                        <Button
                          size="small"
                          icon={<BookOutlined />}
                          loading={savingFaqId === item.id}
                          onClick={() => addToFaq(item)}
                        >
                          加入 FAQ
                        </Button>
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
                  <Text strong>{statusMessage || `${projectDisplayName} 正在检索并回答…`}</Text>
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>

          <div style={{ padding: '16px 20px', borderTop: '1px solid #f0f2f5', background: '#fafbfc' }}>
            {lastFailedQuestion && !loading && (
              <div style={{ marginBottom: 10, display: 'flex', justifyContent: 'flex-end' }}>
                <Button size="small" icon={<SyncOutlined />} onClick={handleRetry}>
                  重试上一问
                </Button>
              </div>
            )}
            <Space.Compact style={{ width: '100%' }}>
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
              <Button
                type="primary"
                size="large"
                icon={<SendOutlined />}
                onClick={handleSend}
                disabled={loading || !currentRepoId || !input.trim()}
              >
                {loading ? '…' : '发送'}
              </Button>
            </Space.Compact>
          </div>
        </Card>
      </div>
    </PageShell>
  )
}
