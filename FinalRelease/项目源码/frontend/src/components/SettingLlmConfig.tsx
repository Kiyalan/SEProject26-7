import { useEffect, useState } from 'react'
import { Input, message } from 'antd'
import { fetchLlmConfig, setLlmConfig, type LlmConfig } from '../api/generated'
import SettingSection, { SettingStatusRow } from './SettingSection'

const emptyConfig: LlmConfig = { baseUrl: '', apiKey: '', model: '' }

export default function SettingLlmConfig() {
  const [config, setConfig] = useState<LlmConfig>(emptyConfig)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const load = () => {
    setLoading(true)
    fetchLlmConfig()
      .then(({ data }) => setConfig(data))
      .catch(() => {
        setConfig(emptyConfig)
        message.error('读取 LLM 配置失败')
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [])

  const handleSave = async () => {
    setSaving(true)
    try {
      const { data: saved } = await setLlmConfig({
        body: {
          baseUrl: config.baseUrl.trim(),
          apiKey: config.apiKey.trim(),
          model: config.model.trim(),
        },
      })
      setConfig(saved)
      message.success('LLM 配置已保存')
    } catch (err) {
      message.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const configured = Boolean(config.apiKey.trim())
  const disabled = loading || saving

  return (
    <SettingSection
      title="LLM 配置"
      footer={
        <>
          <button type="button" className="gh-btn gh-btn-primary" onClick={handleSave} disabled={disabled}>
            {saving ? '保存中…' : '保存'}
          </button>
          <button type="button" className="gh-btn" onClick={load} disabled={disabled}>
            {loading ? '加载中…' : '重新加载'}
          </button>
        </>
      }
    >
      <SettingStatusRow
        label="状态"
        value={
          <span className={`gh-label${configured ? ' gh-label-green' : ' gh-label-orange'}`}>
            {configured ? '已配置' : '未配置（检索摘要模式）'}
          </span>
        }
      />

      <div className="setting-field">
        <label className="setting-field-label" htmlFor="llm-base-url">
          Base URL
        </label>
        <Input
          id="llm-base-url"
          value={config.baseUrl}
          onChange={(e) => setConfig((c) => ({ ...c, baseUrl: e.target.value }))}
          placeholder="https://openrouter.ai/api/v1"
          disabled={disabled}
        />
      </div>

      <div className="setting-field">
        <label className="setting-field-label" htmlFor="llm-api-key">
          API Key
        </label>
        <Input.Password
          id="llm-api-key"
          value={config.apiKey}
          onChange={(e) => setConfig((c) => ({ ...c, apiKey: e.target.value }))}
          placeholder="sk-or-v1-..."
          disabled={disabled}
        />
      </div>

      <div className="setting-field">
        <label className="setting-field-label" htmlFor="llm-model">
          Model
        </label>
        <Input
          id="llm-model"
          value={config.model}
          onChange={(e) => setConfig((c) => ({ ...c, model: e.target.value }))}
          placeholder="openai/gpt-oss-20b:free"
          disabled={disabled}
        />
      </div>
    </SettingSection>
  )
}
