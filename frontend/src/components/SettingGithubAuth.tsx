import { getUsername, isAuthenticated } from '../lib/auth'
import SettingSection, { SettingStatusRow } from './SettingSection'

export default function SettingGithubAuth() {
  const connected = isAuthenticated()
  const username = getUsername()

  return (
    <SettingSection title="GitHub 接入">
      <SettingStatusRow
        label="OAuth 状态"
        value={
          <span className={`gh-label${connected ? ' gh-label-green' : ' gh-label-orange'}`}>
            {connected ? `已连接${username ? `（${username}）` : ''}` : '未连接'}
          </span>
        }
      />
      <p className="setting-section-desc">
        通过 GitHub OAuth 登录后即可访问仓库与知识库功能。
      </p>
    </SettingSection>
  )
}
