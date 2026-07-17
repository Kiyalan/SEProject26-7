import { projectDisplayName } from '../../config/BaseConfig'
import { MarkGithubIcon, SearchIcon } from '@primer/octicons-react'
import { Avatar, Input, Select } from 'antd'
import { useNavigate } from 'react-router-dom'
import { fetchCurrentUser, fetchLlmConfig } from '../../lib/api'
import type { GithubUser } from '../../lib/BackendTypes'
import { clearAuth, getUsername } from '../../lib/auth'
import { useRepoContext } from '../../context/RepoContext'
import { useEffect, useState } from 'react'

export default function GitHubHeader() {
  const navigate = useNavigate()
  const { repoList, currentRepoId, setCurrentRepo } = useRepoContext()
  const [user, setUser] = useState<GithubUser | null>(null)
  const [search, setSearch] = useState('')
  const [llmOn, setLlmOn] = useState(false)

  useEffect(() => {
    fetchCurrentUser()
      .then(setUser)
      .catch(() => setUser(null))
    fetchLlmConfig()
      .then((c) => setLlmOn(!!c.apiKey))
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
    <header className="gh-header">
      <a className="gh-header-brand" href="/repos" onClick={(e) => { e.preventDefault(); navigate('/repos') }}>
        <MarkGithubIcon size={24} />
        <span>{projectDisplayName}</span>
      </a>

      <div className="gh-header-search">
        <Input
          prefix={<SearchIcon size={16} />}
          placeholder="搜索或跳转（仓库 / Issue / 知识库）"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onPressEnter={() => handleSearch(search)}
          allowClear
          style={{ borderRadius: 6 }}
        />
      </div>

      {repoList.length > 0 && (
        <Select
          value={currentRepoId || undefined}
          onChange={setCurrentRepo}
          style={{ width: 220 }}
          placeholder="当前仓库"
          options={repoList.map((r) => ({ value: r.id, label: r.fullName }))}
          showSearch
          optionFilterProp="label"
        />
      )}

      <div className="gh-header-actions">
        <span className={`gh-label${llmOn ? ' gh-label-green' : ''}`} title={llmOn ? 'LLM 已启用' : 'LLM 未配置'}>
          {llmOn ? 'LLM' : '检索模式'}
        </span>
        <button type="button" className="gh-btn gh-btn-sm" onClick={() => navigate('/settings')}>
          设置
        </button>
        <button type="button" className="gh-btn gh-btn-sm" onClick={handleLogout}>
          退出
        </button>
        {user?.avatarUrl ? (
          <Avatar size={32} src={user.avatarUrl} alt={user.login} />
        ) : (
          <Avatar size={32}>{(user?.login || getUsername() || '?')[0]?.toUpperCase()}</Avatar>
        )}
      </div>
    </header>
  )
}
