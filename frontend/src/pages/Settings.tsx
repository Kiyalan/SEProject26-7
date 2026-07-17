import { Alert, Button, Form, Input, message } from 'antd'
import { useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import { fetchBackendHealth, fetchLlmConfig, testLlmConfig, updateLlmConfig } from '../lib/api'

type LlmConfig = Awaited<ReturnType<typeof fetchLlmConfig>>

export default function Settings() {
  const [llm, setLlm] = useState<LlmConfig | null>(null)
  const [health, setHealth] = useState<{
    pid: number
    startedAt: string
    llmConfigured: boolean
  } | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [form] = Form.useForm<{
    apiKey: string
    baseUrl: string
    model: string
    httpReferer: string
    appTitle: string
  }>()

  const refresh = () => {
    setLoading(true)
    Promise.all([fetchLlmConfig(), fetchBackendHealth()])
      .then(([cfg, h]) => {
        setLlm(cfg)
        setHealth(h)
        form.setFieldsValue({
          apiKey: '',
          baseUrl: cfg.baseUrl ?? 'https://openrouter.ai/api/v1',
          model: cfg.model ?? 'tencent/hy3:free',
          httpReferer: cfg.httpReferer ?? 'http://localhost:5173',
          appTitle: cfg.appTitle ?? 'RepoPilot',
        })
      })
      .catch(() => {
        setLlm(null)
        setHealth(null)
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    refresh()
  }, [])

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: Parameters<typeof updateLlmConfig>[0] = {
        baseUrl: values.baseUrl.trim(),
        model: values.model.trim(),
        httpReferer: values.httpReferer.trim(),
        appTitle: values.appTitle.trim(),
      }
      if (values.apiKey.trim()) {
        payload.apiKey = values.apiKey.trim()
      }
      const cfg = await updateLlmConfig(payload)
      setLlm(cfg)
      form.setFieldValue('apiKey', '')
      message.success('LLM 配置已保存，立即生效')
      const h = await fetchBackendHealth()
      setHealth(h)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleTest = async () => {
    setTesting(true)
    try {
      const result = await testLlmConfig()
      if (result.success) {
        message.success(result.message)
      } else {
        message.warning(result.message)
      }
    } catch (err) {
      message.error(err instanceof Error ? err.message : '连接测试失败')
    } finally {
      setTesting(false)
    }
  }

  const handleClearKey = async () => {
    setSaving(true)
    try {
      const cfg = await updateLlmConfig({ clearApiKey: true })
      setLlm(cfg)
      form.setFieldValue('apiKey', '')
      message.success('已清除 API Key')
      const h = await fetchBackendHealth()
      setHealth(h)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '清除失败')
    } finally {
      setSaving(false)
    }
  }

  const mismatch = health && llm && health.llmConfigured !== llm.configured

  return (
    <PageShell
      title="Settings"
      description="GitHub 接入与 AI 服务配置"
      actions={
        <button type="button" className="gh-btn" onClick={refresh} disabled={loading}>
          {loading ? '刷新中…' : '刷新状态'}
        </button>
      }
    >
      <div className="gh-grid-2">
        <div className="gh-box">
          <div className="gh-box-header">GitHub 接入</div>
          <div className="gh-box-body">
            <div className="gh-data-row">
              <span className="gh-muted">OAuth 状态</span>
              <span className="gh-label gh-label-green">已连接（登录后）</span>
            </div>
            {health && (
              <>
                <div className="gh-data-row">
                  <span className="gh-muted">后端 PID</span>
                  <span>{health.pid}</span>
                </div>
                <div className="gh-data-row">
                  <span className="gh-muted">启动时间 (UTC)</span>
                  <span style={{ fontSize: 12 }}>{health.startedAt}</span>
                </div>
              </>
            )}
            <p className="gh-muted" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
              也可访问 <a className="gh-link" href="http://localhost:8000/api/health" target="_blank" rel="noreferrer">/api/health</a> 核对。
            </p>
          </div>
        </div>

        <div className="gh-box">
          <div className="gh-box-header">OpenRouter LLM</div>
          <div className="gh-box-body">
            {mismatch && (
              <Alert
                type="warning"
                showIcon
                message="配置不一致：请刷新页面或重启后端"
                style={{ marginBottom: 12 }}
              />
            )}
            <div className="gh-data-row">
              <span className="gh-muted">状态</span>
              <span className={`gh-label${llm?.configured ? ' gh-label-green' : ' gh-label-orange'}`}>
                {llm?.configured ? '已配置' : '未配置（检索摘要模式）'}
              </span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">配置来源</span>
              <span>{llm?.source === 'ui' ? '界面保存' : '环境变量 / .env'}</span>
            </div>
            {llm?.hasApiKey && (
              <div className="gh-data-row">
                <span className="gh-muted">当前 Key</span>
                <span style={{ fontSize: 12 }}>{llm.apiKeyMasked}</span>
              </div>
            )}

            <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
              <Form.Item
                label="API Key"
                name="apiKey"
                extra={
                  llm?.hasApiKey
                    ? '留空则保留当前 Key；保存新 Key 会覆盖原值'
                    : '在 openrouter.ai/keys 创建，格式如 sk-or-v1-...'
                }
              >
                <Input.Password placeholder={llm?.hasApiKey ? '留空保留当前 Key' : 'sk-or-v1-...'} />
              </Form.Item>
              <Form.Item
                label="Base URL"
                name="baseUrl"
                rules={[{ required: true, message: '请填写 Base URL' }]}
              >
                <Input placeholder="https://openrouter.ai/api/v1" />
              </Form.Item>
              <Form.Item
                label="模型"
                name="model"
                rules={[{ required: true, message: '请填写模型 ID' }]}
              >
                <Input placeholder="tencent/hy3:free" />
              </Form.Item>
              <Form.Item label="HTTP Referer" name="httpReferer">
                <Input placeholder="http://localhost:5173" />
              </Form.Item>
              <Form.Item label="应用标题" name="appTitle">
                <Input placeholder="RepoPilot" />
              </Form.Item>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Button type="primary" onClick={handleSave} loading={saving}>
                  保存配置
                </Button>
                <Button onClick={handleTest} loading={testing} disabled={!llm?.configured}>
                  测试连接
                </Button>
                {llm?.hasApiKey && (
                  <Button danger onClick={handleClearKey} loading={saving}>
                    清除 Key
                  </Button>
                )}
              </div>
            </Form>

            <p className="gh-muted" style={{ margin: '16px 0 0', fontSize: 12, lineHeight: 1.7 }}>
              配置保存在后端 <code>data/llm-config.json</code>，保存后立即生效，无需改代码或重启。
              仍可通过 <code>backend/.env</code> 设置初始默认值。
            </p>
          </div>
        </div>
      </div>
    </PageShell>
  )
}
