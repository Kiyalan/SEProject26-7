import { projectDisplayName } from '../config/BaseConfig'
import { Alert, Input } from 'antd'
import { PaperAirplaneIcon, PersonIcon } from '@primer/octicons-react'
import { useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import { useRepoContext } from '../context/RepoContext'
import { fetchKnowledge, fetchLlmConfig, sendChatMessage } from '../lib/api'
import type { ChatMessage } from '../lib/FrontendTypes'

const questionTypeMap = {
  what: { label: 'What', className: 'gh-label gh-label-blue' },
  where: { label: 'Where', className: 'gh-label' },
  how: { label: 'How', className: 'gh-label gh-label-green' },
}

export default function Chat() {
  const { currentRepoId, setCurrentRepo, repoList } = useRepoContext()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [llmEnabled, setLlmEnabled] = useState(false)
  const [knowledgeReady, setKnowledgeReady] = useState(false)

  useEffect(() => {
    fetchLlmConfig()
      .then((cfg) => setLlmEnabled(Boolean(cfg.apiKey?.trim())))
      .catch(() => setLlmEnabled(false))
  }, [])

  useEffect(() => {
    if (!currentRepoId) return
    fetchKnowledge(currentRepoId)
      .then((data) => setKnowledgeReady(data.status === 'ready' && data.chunkCount > 0))
      .catch(() => setKnowledgeReady(false))
  }, [currentRepoId])

  const handleSend = async () => {
    if (!input.trim() || !currentRepoId || loading) return

    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: input,
    }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    setLoading(true)

    try {
      const result = await sendChatMessage(currentRepoId, userMsg.content)
      setMessages((prev) => [
        ...prev,
        {
          id: `a-${Date.now()}`,
          role: 'assistant',
          content: result.answer,
          citations: result.citations,
          questionType: result.questionType,
        },
      ])
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          id: `a-${Date.now()}`,
          role: 'assistant',
          content: err instanceof Error ? err.message : '问答失败',
        },
      ])
    } finally {
      setLoading(false)
    }
  }

  return (
    <PageShell
      title="智能问答"
      description={
        llmEnabled
          ? '基于知识库检索 + LLM 生成回答'
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
          {messages.length === 0 ? (
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
                    <strong>{item.role === 'user' ? '你' : projectDisplayName}</strong>
                    {item.questionType && (
                      <span className={questionTypeMap[item.questionType].className}>
                        {questionTypeMap[item.questionType].label}
                      </span>
                    )}
                  </div>
                  <p style={{ margin: 0, whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{item.content}</p>
                  {item.citations?.map((c) => (
                    <div key={c.file} className="gh-muted" style={{ fontSize: 12, marginTop: 4 }}>
                      引用：{c.file}
                      {c.line ? `:${c.line}` : ''}
                    </div>
                  ))}
                </div>
              </div>
            ))
          )}
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
              disabled={loading || !currentRepoId}
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
