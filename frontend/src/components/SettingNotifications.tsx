import { useEffect, useState } from 'react'
import { Input, Switch, message } from 'antd'
import {
  fetchNotificationSettings,
  sendTestNotification,
  updateNotificationSettings,
  type NotificationSettings,
} from '../api/generated'
import SettingSection, { SettingStatusRow } from './SettingSection'

const empty: NotificationSettings = {
  email: '',
  enabled: false,
  notifyOnKnowledgeBuild: true,
  notifyOnIssueAnalysis: false,
  notifyOnWikiReady: true,
  deliveryMode: 'stub',
  updatedAt: '',
}

export default function SettingNotifications() {
  const [settings, setSettings] = useState<NotificationSettings>(empty)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)

  const load = () => {
    setLoading(true)
    fetchNotificationSettings()
      .then(({ data }) => setSettings(data))
      .catch(() => {
        setSettings(empty)
        message.error('读取通知配置失败')
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [])

  const handleSave = async () => {
    setSaving(true)
    try {
      const { data } = await updateNotificationSettings({
        body: {
          email: settings.email.trim(),
          enabled: settings.enabled,
          notifyOnKnowledgeBuild: settings.notifyOnKnowledgeBuild,
          notifyOnIssueAnalysis: settings.notifyOnIssueAnalysis,
          notifyOnWikiReady: settings.notifyOnWikiReady,
        },
      })
      setSettings(data)
      message.success('通知配置已保存')
    } catch (err) {
      message.error(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleTest = async () => {
    setTesting(true)
    try {
      const { data } = await sendTestNotification()
      message.success(data.message)
      load()
    } catch (err) {
      message.error(err instanceof Error ? err.message : '测试发送失败')
    } finally {
      setTesting(false)
    }
  }

  const disabled = loading || saving || testing

  return (
    <SettingSection
      title="邮件通知（Stub）"
      footer={
        <>
          <button type="button" className="gh-btn gh-btn-primary" onClick={handleSave} disabled={disabled}>
            {saving ? '保存中…' : '保存'}
          </button>
          <button type="button" className="gh-btn" onClick={handleTest} disabled={disabled}>
            {testing ? '发送中…' : '发送测试邮件'}
          </button>
          <button type="button" className="gh-btn" onClick={load} disabled={disabled}>
            {loading ? '加载中…' : '重新加载'}
          </button>
        </>
      }
    >
      <SettingStatusRow
        label="投递模式"
        value={<span className="gh-label">stub · 不真实投递 SMTP</span>}
      />
      <SettingStatusRow
        label="总开关"
        value={
          <Switch
            checked={settings.enabled}
            disabled={disabled}
            onChange={(checked) => setSettings((s) => ({ ...s, enabled: checked }))}
          />
        }
      />

      <div className="setting-field">
        <label className="setting-field-label" htmlFor="notify-email">
          接收邮箱
        </label>
        <Input
          id="notify-email"
          type="email"
          placeholder="you@example.com"
          value={settings.email}
          disabled={disabled}
          onChange={(e) => setSettings((s) => ({ ...s, email: e.target.value }))}
        />
      </div>

      <SettingStatusRow
        label="知识库构建完成"
        value={
          <Switch
            checked={settings.notifyOnKnowledgeBuild}
            disabled={disabled}
            onChange={(checked) => setSettings((s) => ({ ...s, notifyOnKnowledgeBuild: checked }))}
          />
        }
      />
      <SettingStatusRow
        label="Issue 分析完成"
        value={
          <Switch
            checked={settings.notifyOnIssueAnalysis}
            disabled={disabled}
            onChange={(checked) => setSettings((s) => ({ ...s, notifyOnIssueAnalysis: checked }))}
          />
        }
      />
      <SettingStatusRow
        label="Wiki 生成完成"
        value={
          <Switch
            checked={settings.notifyOnWikiReady}
            disabled={disabled}
            onChange={(checked) => setSettings((s) => ({ ...s, notifyOnWikiReady: checked }))}
          />
        }
      />

      {settings.lastTestMessage && (
        <p className="gh-muted" style={{ margin: '8px 0 0', fontSize: 12 }}>
          最近测试：{settings.lastTestAt} — {settings.lastTestMessage}
        </p>
      )}
    </SettingSection>
  )
}
