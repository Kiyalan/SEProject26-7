// @ts-nocheck
import { Card, Typography } from 'antd'
import {
  GithubOutlined,
  SettingOutlined,
  MailOutlined
} from '@ant-design/icons'
import PageShell from '../components/layout/PageShell'
import SettingGithubAuth from '../components/SettingGithubAuth'
import SettingLlmConfig from '../components/SettingLlmConfig'
import SettingNotifications from '../components/SettingNotifications'

//const { Text } = Typography

export default function Settings() {
  return (
    <PageShell title="Settings" description="GitHub 接入、AI 服务与通知配置">
      {/* 最外层容器，与知识库页面间距规范完全统一 */}
      <div className="settings-page" style={{ display: 'flex', flexDirection: 'column', gap: 32 }}>
        {/* ===== 模块1：GitHub 接入 ===== */}
        <Card
          style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
          bodyStyle={{ padding: '24px 28px' }}
          title={
            <span style={{ fontWeight: 700, fontSize: 16 }}>
              <GithubOutlined style={{ marginRight: 8 }} />
              GitHub 接入
            </span>
          }
        >
          <SettingGithubAuth />
        </Card>

        {/* ===== 模块2：LLM 配置 ===== */}
        <Card
          style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
          bodyStyle={{ padding: '24px 28px' }}
          title={
            <span style={{ fontWeight: 700, fontSize: 16 }}>
              <SettingOutlined style={{ marginRight: 8 }} />
              LLM 配置
            </span>
          }
        >
          <SettingLlmConfig />
        </Card>

        {/* ===== 模块3：邮件通知 ===== */}
        <Card
          style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
          bodyStyle={{ padding: '24px 28px' }}
          title={
            <span style={{ fontWeight: 700, fontSize: 16 }}>
              <MailOutlined style={{ marginRight: 8 }} />
              邮件通知
            </span>
          }
        >
          <SettingNotifications />
        </Card>
      </div>
    </PageShell>
  )
}