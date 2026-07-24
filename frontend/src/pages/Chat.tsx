import { projectDisplayName } from '../config/BaseConfig'
import { Alert, Input, Spin } from 'antd'
import { PaperAirplaneIcon, PersonIcon, SyncIcon } from '@primer/octicons-react'
import { useEffect, useRef, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import { fetchKnowledge, fetchLlmConfig } from '../api/generated'
import { getToken } from '../lib/AuthAxios'
import type { ChatMessage } from '../lib/FrontendTypes'

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

export default function Chat() {
  const { currentRepoId, setCurrentRepo, repoList } = useRepoContext()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [llmEnabled, setLlmEnabled] = useState(false)
  const [knowledgeReady, setKnowledgeReady] = useState(false)
  const [lastFailedQuestion, setLastFailedQuestion] = useState<string | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const requestSeq = useRef(0)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    fetchLlmConfig()
      .then(({ data: cfg }) => setLlmEnabled(Boolean(cfg.apiKey?.trim())))
      .catch(() => setLlmEnabled(false))
  }, [])

  useEffect(() => {
    requestSeq.current += 1
    // 终止上一次请求
    abortRef.current?.abort()
    abortRef.current = null
    setMessages([])
    setInput('')
    setLastFailedQuestion(null)
    setLoading(false)
    if (!currentRepoId) {
      setKnowledgeReady(false)
      return
    }
    fetchKnowledge({ path: { repoId: currentRepoId } })
      .then(({ data }) => setKnowledgeReady(data.status === 'ready' && data.chunkCount > 0))
      .catch(() => setKnowledgeReady(false))
  }, [currentRepoId])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  const askStream = async (question: string) => {
    const trimmed = question.trim()
    if (!trimmed || !currentRepoId || loading) return
    if (trimmed.length > MAX_QUESTION_LENGTH) {
      setMessages((prev) => [
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

    const seq = ++requestSeq.current
    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: trimmed,
    }
    setMessages((prev) => [...prev, userMsg])
    setLoading(true)
    setLastFailedQuestion(null)

    // 创建流式请求
    const controller = new AbortController()
    abortRef.current = controller
    const token = getToken()
    const assistId = `a-${Date.now()}`
    let metaReceived = false

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
        const errorText = await response.text().catch(() => '问答失败')
        if (seq !== requestSeq.current) return
        setLastFailedQuestion(trimmed)
        setMessages((prev) => [
          ...prev,
          { id: assistId, role: 'assistant', content: errorText, error: true },
        ])
        return
      }

      const reader = response.body?.getReader()
      if (!reader) {
        if (seq !== requestSeq.current) return
        setMessages((prev) => [...prev, { id: assistId, role: 'assistant', content: '无法读取流式响应', error: true }])
        return
      }

      const decoder = new TextDecoder()
      let buffer = ''
      let accumulated = ''
      let meta: { questionType?: string; citations?: unknown[]; intent?: string; llmEnabled?: boolean } = {}

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
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
            const json = trimmedLine.slice(5).trim()
            try {
              const event = JSON.parse(json)

              if (currentEvent === 'meta') {
                meta = event
                metaReceived = true
                setMessages((prev) => [
                  ...prev,
                  {
                    id: assistId,
                    role: 'assistant',
                    content: '',
                    questionType: event.questionType,
                    citations: event.citations,
                    intent: event.intent,
                    error: false,
                    streaming: true,
                  },
                ])
              } else if (currentEvent === 'token') {
                accumulated += event.content
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === assistId
                      ? { ...m, content: m.content + event.content, streaming: true }
                      : m,
                  ),
                )
              } else if (currentEvent === 'done') {
                const finalContent = event.answer || accumulated
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === assistId
                      ? { ...m, content: finalContent, streaming: false }
                      : m,
                  ),
                )
              } else if (currentEvent === 'error') {
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === assistId
                      ? { ...m, content: event, error: true, streaming: false }
                      : m,
                  ),
                )
              } else if (currentEvent === 'data') {
                // 非流式回退：直接渲染完整内容
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === assistId
                      ? { ...m, content: event.content ?? '', streaming: false, questionType: event.questionType, citations: event.citations, intent: event.intent }
                      : m,
                  ),
                )
              }
              currentEvent = ''
            } catch {
              // 跳过非 JSON 行
            }
          }
        }
      }

      // 确保 streaming 标记关闭
      setMessages((prev) =>
        prev.map((m) => (m.id === assistId ? { ...m, streaming: false } : m)),
      )
      if (!accumulated && seq === requestSeq.current) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistId
              ? { ...m, content: 'LLM 返回了空响应，请重试', error: true }
              : m,
          ),
        )
      }
    } catch (err) {
      if (seq !== requestSeq.current) return
      setLastFailedQuestion(trimmed)
      if ((err as Error).name === 'AbortError') return
      setMessages((prev) => [
        ...prev,
        {
          id: assistId,
          role: 'assistant',
          content: err instanceof Error ? err.message : '流式问答失败',
          error: true,
        },
      ])
    } finally {
      if (seq === requestSeq.current) {
        setLoading(false)
        abortRef.current = null
      }
    }
  }

  const handleSend = async () => {
    const question = input.trim()
    if (!question) return
    setInput('')
    await askStream(question)
  }

  const handleRetry = async () => {
    if (!lastFailedQuestion) return
    await askStream(lastFailedQuestion)
  }

  return (
    <PageShell
      title="智能问答"
      description={
        llmEnabled
          ? '基于知识库检索 + LLM 流式生成回答'
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
                    {(item as ChatMessage & { emptyEvidence?: boolean }).emptyEvidence && (
                      <span className="gh-label gh-label-orange">无证据</span>
                    )}
                    {item.error && <span className="gh-label gh-label-red">失败</span>}
                    {(item as ChatMessage & { streaming?: boolean }).streaming && (
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
                </div>
              </div>
            ))
          )}

          {loading && (
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 8 }}>
              <Spin size="small" />
              <div>
                <div className="rp-loading-pulse" style={{ fontWeight: 600 }}>
                  {projectDisplayName} 正在检索并回答…
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
              onChange={(e) => setInput(e.target.value)}
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
