import { projectDisplayName } from '../../config/BaseConfig'
import { MarkGithubIcon, SearchIcon } from '@primer/octicons-react'
import { Avatar, Input, Select } from 'antd'
import { useNavigate } from 'react-router-dom'
import { fetchUserProfile, fetchLlmConfig, type UserProfile } from '../../api/generated'
import { clearAuth, getUsername } from '../../lib/AuthAxios'
import { useRepoContext } from '../../context/RepoContext'
import { useEffect, useState } from 'react'

export default function GitHubHeader() {
  const navigate = useNavigate()
  const { repoList, currentRepoId, setCurrentRepo } = useRepoContext()
  const [user, setUser] = useState<UserProfile | null>(null)
  const [search, setSearch] = useState('')
  const [llmOn, setLlmOn] = useState(false)

  useEffect(() => {
    fetchUserProfile()
      .then(({ data }) => setUser(data))
      .catch(() => setUser(null))
    fetchLlmConfig()
      .then(({ data: c }) => setLlmOn(Boolean(c.apiKey?.trim())))
      .catch(() => setLlmOn(false))
  }, [])

  const handleLogout = () => {
    clearAuth()
    navigate('/login')
  }

  const handleSearch = (value: string) => {
    const q = value.trim().toLowerCase()
    if (!q) return
    if (q.includes('issue')) navigate('/issues')
    else if (q.includes('知识') || q.includes('knowledge')) navigate('/knowledge')
    else if (q.includes('问答') || q.includes('chat')) navigate('/chat')
    else navigate('/repos')
  }

  return (
    <header
      className="gh-header"
      style={{
        height: 64,
        padding: '0 24px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 24,
        background: '#ffffff',
        borderBottom: '1px solid #e5e7eb',
        boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)',
      }}
    >
      {/* 左侧品牌区 */}
      <a
        className="gh-header-brand"
        href="/repos"
        onClick={(e) => {
          e.preventDefault()
          navigate('/repos')
        }}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          fontSize: 16,
          fontWeight: 600,
          color: '#111827',
          textDecoration: 'none',
        }}
      >
        <MarkGithubIcon size={24} />
        <span>{projectDisplayName}</span>
      </a>

      {/* 中间搜索 + 仓库选择 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flex: 1, maxWidth: 640 }}>
        <div className="gh-header-search" style={{ flex: 1 }}>
          <Input
            prefix={<SearchIcon size={16} />}
            placeholder="搜索或跳转（仓库 / Issue / 知识库）"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onPressEnter={() => handleSearch(search)}
            allowClear
            style={{ borderRadius: 8, height: 36 }}
          />
        </div>

        {repoList.length > 0 && (
          <Select
            value={currentRepoId || undefined}
            onChange={setCurrentRepo}
            style={{ width: 240 }}
            placeholder="当前仓库"
            options={repoList.map((r) => ({ value: r.id, label: r.fullName }))}
            showSearch
            optionFilterProp="label"
          />
        )}
      </div>

      {/* 右侧操作区：强化按钮 + 高亮用户信息 */}
      <div className="gh-header-actions" style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <span
          className={`gh-label${llmOn ? ' gh-label-green' : ''}`}
          title={llmOn ? 'LLM 已启用' : 'LLM 未配置'}
          style={{
            padding: '4px 10px',
            borderRadius: 6,
            fontSize: 12,
            fontWeight: 500,
            background: llmOn ? '#f0fdf4' : '#fef3c7',
            color: llmOn ? '#166534' : '#92400e',
          }}
        >
          {llmOn ? 'LLM 已启用' : '检索模式'}
        </span>

        <button
          type="button"
          className="gh-btn gh-btn-sm"
          onClick={() => navigate('/settings')}
          style={{
            height: 36,
            padding: '0 14px',
            borderRadius: 8,
            fontSize: 13,
            fontWeight: 500,
            border: '1px solid #d1d5db',
            background: '#ffffff',
            cursor: 'pointer',
            transition: 'all 0.2s',
          }}
        >
          设置
        </button>

        <button
          type="button"
          className="gh-btn gh-btn-sm"
          onClick={handleLogout}
          style={{
            height: 36,
            padding: '0 14px',
            borderRadius: 8,
            fontSize: 13,
            fontWeight: 500,
            color: '#dc2626',
            border: '1px solid #fecaca',
            background: '#fef2f2',
            cursor: 'pointer',
            transition: 'all 0.2s',
          }}
        >
          退出
        </button>

        {/* 用户账号区域：加底色高亮，加大头像，显示用户名 */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '6px 14px',
            background: '#f0f7ff',
            border: '1px solid #d6e4ff',
            borderRadius: 8,
            cursor: 'pointer',
            transition: 'all 0.2s',
          }}
        >
          {user?.avatarUrl ? (
            <Avatar size={36} src={user.avatarUrl} alt={user.login} />
          ) : (
            <Avatar size={36}>
              {(user?.login || getUsername() || '?').toUpperCase().charAt(0)}
            </Avatar>
          )}
          <span style={{ fontSize: 13, fontWeight: 600, color: '#165DFF' }}>
            {user?.login || getUsername()}
          </span>
        </div>
      </div>
    </header>
  )
}