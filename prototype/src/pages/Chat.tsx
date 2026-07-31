import { Alert, Input, Select } from 'antd'
import {
  CommentDiscussionIcon,
  GitCommitIcon,
  PaperAirplaneIcon,
  PersonIcon,
  PlusIcon,
  TrashIcon,
} from '@primer/octicons-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import {
  deleteConversation,
  fetchConversationMessages,
  fetchConversations,
  fetchKnowledge,
  fetchKnowledgeCommits,
  fetchLlmConfig,
  sendChatMessageStream,
  type ConversationSummary,
  type ConvMessage,
  type IndexedCommit,
} from '../lib/api'
import type { ChatMessage } from '../types'

const qtm = {
  what: { label: 'What', className: 'gh-label gh-label-blue' },
  where: { label: 'Where', className: 'gh-label' },
  how: { label: 'How', className: 'gh-label gh-label-green' },
}

function convMsgToChat(msg: ConvMessage): ChatMessage {
  return {
    id: msg.id,
    role: msg.role,
    content: msg.content,
    citations: msg.citations,
    questionType: msg.questionType as ChatMessage['questionType'],
  }
}

export default function Chat() {
  const { repoId, setRepoId, repos } = useRepoContext()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [llmEnabled, setLlmEnabled] = useState(false)
  const [knowledgeReady, setKnowledgeReady] = useState(false)
  const [commits, setCommits] = useState<IndexedCommit[]>([])
  const [selectedCommit, setSelectedCommit] = useState('')

  // ── 对话历史状态 ──
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [convId, setConvId] = useState('')
  const [convLoading, setConvLoading] = useState(false)

  const bottomRef = useRef<HTMLDivElement>(null)

  // ── 初始化 LLM / 知识库 / commits ──
  useEffect(() => {
    fetchLlmConfig()
      .then((cfg) => setLlmEnabled(cfg.configured))
      .catch(() => setLlmEnabled(false))
  }, [])

  useEffect(() => {
    if (!repoId) return
    fetchKnowledge(repoId)
      .then((data) => setKnowledgeReady(data.status === 'ready' && data.chunkCount > 0))
      .catch(() => setKnowledgeReady(false))
    fetchKnowledgeCommits(repoId)
      .then((data) => {
        setCommits(data.items || [])
        if (data.items?.length) setSelectedCommit((p) => p || data.items[0].commitSha)
      })
      .catch(() => setCommits([]))
  }, [repoId])

  // ── 加载对话列表 ──
  const loadConversations = useCallback(async () => {
    if (!repoId) return
    try {
      const { items } = await fetchConversations(repoId)
      setConversations(items)
    } catch {
      setConversations([])
    }
  }, [repoId])

  useEffect(() => {
    loadConversations()
  }, [loadConversations])

  // ── 切换到指定对话 ──
  const switchConv = useCallback(async (id: string) => {
    setConvId(id)
    setConvLoading(true)
    try {
      const { items } = await fetchConversationMessages(id)
      setMessages(items.map(convMsgToChat))
    } catch {
      setMessages([])
    } finally {
      setConvLoading(false)
    }
  }, [])

  // 新对话
  const newConv = useCallback(() => {
    setConvId('')
    setMessages([])
  }, [])

  // 清除当前对话记录
  const handleClear = useCallback(() => {
    setMessages([])
  }, [])

  // 删除对话
  const removeConv = useCallback(
    async (id: string) => {
      try {
        await deleteConversation(id)
        if (convId === id) {
          setConvId('')
          setMessages([])
        }
        loadConversations()
      } catch {
        // ignore
      }
    },
    [convId, loadConversations],
  )

  // ── 自动滚动 ──
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // ── 发送消息 ──
  const handleSend = async () => {
    if (!input.trim() || !repoId || loading) return

    const userMsg: ChatMessage = { id: `u-${Date.now()}`, role: 'user', content: input }
    setMessages((prev) => [...prev, userMsg])
    const sentInput = input
    setInput('')
    setLoading(true)

    const assistantId = `a-${Date.now()}`
    setMessages((prev) => [...prev, { id: assistantId, role: 'assistant', content: '' }])

    try {
      const newConvId = await sendChatMessageStream(
        repoId,
        sentInput,
        selectedCommit || undefined,
        convId || undefined,
        (chunk) => {
          setMessages((prev) =>
            prev.map((msg) => {
              if (msg.id !== assistantId) return msg
              if (chunk.type === 'header') {
                return {
                  ...msg,
                  questionType: chunk.questionType as ChatMessage['questionType'],
                  citations: chunk.citations,
                  content: msg.content,
                }
              }
              if (chunk.type === 'content') {
                return { ...msg, content: msg.content + (chunk.content || '') }
              }
              if (chunk.type === 'error') {
                return { ...msg, content: msg.content + `\n\n（${chunk.content}）` }
              }
              return msg
            }),
          )
        },
      )
      if (newConvId && !convId) {
        setConvId(newConvId)
        loadConversations()
      } else {
        loadConversations()
      }
    } catch (err) {
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: err instanceof Error ? err.message : '问答失败' }
            : msg,
        ),
      )
    } finally {
      setLoading(false)
    }
  }

  const commitOptions = useMemo(
    () =>
      commits.map((c) => ({
        value: c.commitSha,
        label: `${c.shortSha} — ${c.message.slice(0, 40)}`,
      })),
    [commits],
  )

  return (
    <PageShell
      title="智能问答"
      description={
        llmEnabled ? '基于知识库检索 + LLM 流式生成回答' : '检索摘要模式（配置 LLM_API_KEY 可启用大模型）'
      }
      actions={
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {commits.length > 1 && (
            <Select
              value={selectedCommit}
              onChange={setSelectedCommit}
              style={{ minWidth: 180, fontSize: 12 }}
              options={commitOptions}
              prefix={<GitCommitIcon size={12} />}
            />
          )}
          <select
            className="gh-btn"
            value={repoId}
            onChange={(e) => setRepoId(e.target.value)}
            style={{ minWidth: 220 }}
          >
            {repos.map((r) => (
              <option key={r.id} value={r.id}>
                {r.fullName}
              </option>
            ))}
          </select>
        </div>
      }
    >
      {!knowledgeReady && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="当前仓库尚未构建知识库"
          description="请先在「知识库」页面构建索引。"
        />
      )}

      <div style={{ display: 'flex', gap: 0, minHeight: 520 }}>
        {/* ── 左侧：对话列表 ── */}
        <div
          style={{
            width: 220,
            flexShrink: 0,
            borderRight: '1px solid var(--gh-border-muted)',
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          <div
            style={{
              padding: '8px 12px',
              borderBottom: '1px solid var(--gh-border-muted)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <span style={{ fontSize: 13, fontWeight: 600 }}>历史对话</span>
            <button
              type="button"
              className="gh-btn gh-btn-sm"
              onClick={newConv}
              title="新对话"
            >
              <PlusIcon size={14} />
            </button>
          </div>
          <div style={{ flex: 1, overflowY: 'auto' }}>
            {conversations.length === 0 ? (
              <p className="gh-muted" style={{ padding: 16, fontSize: 12 }}>
                暂无对话记录
              </p>
            ) : (
              conversations.map((c) => (
                <div
                  key={c.id}
                  className={c.id === convId ? 'gh-sidebar-link active' : 'gh-sidebar-link'}
                  style={{
                    cursor: 'pointer',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '6px 12px',
                    fontSize: 13,
                    lineHeight: 1.4,
                  }}
                  onClick={() => switchConv(c.id)}
                >
                  <div style={{ flex: 1, minWidth: 0, overflow: 'hidden' }}>
                    <div
                      style={{
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      <CommentDiscussionIcon size={12} /> {c.title}
                    </div>
                    <div className="gh-muted" style={{ fontSize: 11 }}>
                      {c.msgCount} 条消息 · {c.updatedAt.slice(5, 16)}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="gh-btn gh-btn-sm"
                    style={{ flexShrink: 0, opacity: 0.5 }}
                    onClick={(e) => {
                      e.stopPropagation()
                      removeConv(c.id)
                    }}
                    title="删除对话"
                  >
                    <TrashIcon size={12} />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>

        {/* ── 右侧：聊天区 ── */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <div style={{ flex: 1, overflowY: 'auto', padding: '12px 16px' }}>
            {convLoading ? (
              <p className="gh-muted" style={{ textAlign: 'center', padding: 40 }}>
                加载中…
              </p>
            ) : messages.length === 0 ? (
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
                      background: item.role === 'user' ? '#ddf4ff' : '#dafbe1',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                    }}
                  >
                    {item.role === 'user' ? <PersonIcon size={16} /> : <span>🤖</span>}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                      <strong>{item.role === 'user' ? '你' : 'RepoPilot'}</strong>
                      {item.questionType && (
                        <span className={qtm[item.questionType]?.className}>{qtm[item.questionType]?.label}</span>
                      )}
                    </div>
                    {item.role === 'assistant' ? (
                      <div className="chat-markdown">
                        <ReactMarkdown>{item.content || '_正在生成..._'}</ReactMarkdown>
                      </div>
                    ) : (
                      <p style={{ margin: 0, whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{item.content}</p>
                    )}
                    {item.citations?.map((c) => (
                      <div key={c.file} className="gh-muted" style={{ fontSize: 12, marginTop: 4 }}>
                        引用：<code>{c.file}</code>
                        {c.line ? `:${c.line}` : ''}
                      </div>
                    ))}
                  </div>
                </div>
              ))
            )}
            <div ref={bottomRef} />
          </div>

          <div style={{ padding: '12px 16px', borderTop: '1px solid var(--gh-border-muted)' }}>
            <div style={{ display: 'flex', gap: 8 }}>
              <Input
                size="large"
                placeholder="例如：路由配置在哪里？如何运行测试？"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onPressEnter={handleSend}
                disabled={loading}
              />
              <button
                type="button"
                className="gh-btn gh-btn-primary"
                onClick={handleSend}
                disabled={loading || !repoId}
                style={{ height: 40, padding: '0 16px' }}
              >
                <PaperAirplaneIcon size={16} />
                {loading ? '…' : '发送'}
              </button>
              {messages.length > 0 && (
                <button
                  type="button"
                  className="gh-btn"
                  onClick={handleClear}
                  title="清除当前聊天记录"
                  style={{ height: 40, padding: '0 12px' }}
                >
                  <TrashIcon size={14} />
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
