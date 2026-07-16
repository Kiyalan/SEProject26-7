import PageShell from '../components/layout/PageShell'
import SettingGithubAuth from '../components/SettingGithubAuth'
import SettingLlmConfig from '../components/SettingLlmConfig'

export default function Settings() {
  return (
    <PageShell title="Settings" description="GitHub 接入与 AI 服务配置">
      <div className="settings-stack">
        <SettingGithubAuth />
        <SettingLlmConfig />
      </div>
    </PageShell>
  )
}
