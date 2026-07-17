import { Alert, Button, Form, Input, message } from 'antd'
import { useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import { fetchLlmConfig, updateLlmConfig } from '../lib/api'

type LlmConfig = Awaited<ReturnType<typeof fetchLlmConfig>>

export default function Settings() {
  const [llm, setLlm] = useState<LlmConfig | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<{
    apiKey: string
    baseUrl: string
    model: string
  }>()

  const refresh = () => {
    setLoading(true)
    fetchLlmConfig()
      .then((cfg) => {
        setLlm(cfg)
        form.setFieldsValue({
          apiKey: '',
          baseUrl: cfg.baseUrl ?? 'https://openrouter.ai/api/v1',
          model: cfg.model ?? 'tencent/hy3:free',
        })
      })
      .catch(() => setLlm(null))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    refresh()
  }, [])

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const cfg = await updateLlmConfig({
        baseUrl: values.baseUrl.trim(),
        apiKey: values.apiKey.trim(),
        model: values.model.trim(),
      })
      setLlm(cfg)
      form.setFieldValue('apiKey', '')
      message.success('LLM 配置已保存，立即生效')
    } catch (err) {
      message.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const configured = !!(llm?.apiKey)

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
          <div className="gh-box-header">OpenRouter LLM</div>
          <div className="gh-box-body">
            <div className="gh-data-row">
              <span className="gh-muted">状态</span>
              <span className={`gh-label${configured ? ' gh-label-green' : ' gh-label-orange'}`}>
                {configured ? '已配置' : '未配置（检索摘要模式）'}
              </span>
            </div>

            <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
              <Form.Item
                label="API Key"
                name="apiKey"
                extra={
                  configured
                    ? '留空则保留当前 Key；保存新 Key 会覆盖原值'
                    : '在 openrouter.ai/keys 创建，格式如 sk-or-v1-...'
                }
              >
                <Input.Password placeholder={configured ? '留空保留当前 Key' : 'sk-or-v1-...'} />
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
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Button type="primary" onClick={handleSave} loading={saving}>
                  保存配置
                </Button>
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
