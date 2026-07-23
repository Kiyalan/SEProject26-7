import PageShell from '../components/layout/PageShell'
import SettingGithubAuth from '../components/SettingGithubAuth'
import SettingLlmConfig from '../components/SettingLlmConfig'
import SettingNotifications from '../components/SettingNotifications'

export default function Settings() {
  return (
    <PageShell title="Settings" description="GitHub 接入、AI 服务与通知配置">
      <div className="settings-stack">
        <SettingGithubAuth />
        <SettingLlmConfig />
        <SettingNotifications />
      </div>
    </PageShell>
  )
}
