import { projectDisplayNameLower } from '../config/BaseConfig'
import { Alert, Modal, Input, message } from 'antd'
import { useState } from 'react'
import { useRepoContext } from '../context/RepoContext'
import {
  buildKnowledge,
  executeGitAction,
  executeNlCommand,
} from '../lib/api'

interface QuickActionsProps {
  compact?: boolean
}

export default function QuickActions({ compact }: QuickActionsProps) {
  const { currentRepoId, currentRepo, syncRepoList } = useRepoContext()
  const [loading, setLoading] = useState<string | null>(null)
  const [nlCommand, setNlCommand] = useState('')
  const [nlResult, setNlResult] = useState<string | null>(null)
  const [modal, setModal] = useState<{
    type: 'branch' | 'commit' | 'pr'
    open: boolean
  }>({ type: 'branch', open: false })
  const [form, setForm] = useState({
    branch: '',
    path: 'README.md',
    content: '',
    message: '',
    prTitle: '',
    prBody: '',
  })

  const run = async (key: string, fn: () => Promise<unknown>) => {
    if (!currentRepoId) {
      message.warning('请先选择仓库')
      return
    }
    setLoading(key)
    try {
      const result = await fn()
      message.success('操作成功')
      return result
    } catch (err) {
      message.error(err instanceof Error ? err.message : '操作失败')
    } finally {
      setLoading(null)
    }
  }

  const handleSync = () =>
    run('sync', async () => {
      await buildKnowledge(currentRepoId)
      await syncRepoList()
    })

  const handleNl = async () => {
    if (!nlCommand.trim() || !currentRepoId) return
    setLoading('nl')
    setNlResult(null)
    try {
      const result = await executeNlCommand(currentRepoId, nlCommand.trim())
      setNlResult(result.message)
      if (result.success) message.success('命令已执行')
      else message.info(result.message)
    } catch (err) {
      setNlResult(err instanceof Error ? err.message : '解析失败')
    } finally {
      setLoading(null)
    }
  }

  const handleModalOk = async () => {
    if (!currentRepoId) return
    setLoading(modal.type)
    try {
      if (modal.type === 'branch') {
        await executeGitAction(currentRepoId, 'create_branch', { branch: form.branch })
      } else if (modal.type === 'commit') {
        await executeGitAction(currentRepoId, 'commit_file', {
          path: form.path,
          content: form.content,
          message: form.message || `Update ${form.path}`,
        })
      } else {
        await executeGitAction(currentRepoId, 'create_pr', {
          title: form.prTitle,
          body: form.prBody,
          head: form.branch || `${projectDisplayNameLower}-${Date.now()}`,
        })
      }
      message.success('操作成功')
      setModal((m) => ({ ...m, open: false }))
    } catch (err) {
      message.error(err instanceof Error ? err.message : '操作失败')
    } finally {
      setLoading(null)
    }
  }

  if (!currentRepoId) {
    return (
      <div className="gh-box">
        <div className="gh-box-body">
          <p className="gh-muted" style={{ margin: 0 }}>选择仓库后可使用快捷操作</p>
        </div>
      </div>
    )
  }

  return (
    <>
      <div className="gh-box">
        <div className="gh-box-header">快捷操作</div>
        <div className="gh-box-body">
          {!compact && (
            <p className="gh-muted" style={{ margin: '0 0 12px', fontSize: 12 }}>
              无需终端，通过按钮或自然语言调用 GitHub API 完成同步、提交与 PR。
            </p>
          )}
          <div className="gh-action-list">
            <button
              type="button"
              className="gh-btn"
              disabled={loading === 'sync'}
              onClick={handleSync}
            >
              {loading === 'sync' ? '同步中…' : '同步知识库（Pull + 索引）'}
            </button>
            <button
              type="button"
              className="gh-btn"
              onClick={() => setModal({ type: 'branch', open: true })}
            >
              创建分支
            </button>
            <button
              type="button"
              className="gh-btn"
              onClick={() => setModal({ type: 'commit', open: true })}
            >
              提交文件更改
            </button>
            <button
              type="button"
              className="gh-btn gh-btn-primary"
              onClick={() => setModal({ type: 'pr', open: true })}
            >
              创建 Pull Request
            </button>
          </div>
        </div>
      </div>

      <div className="gh-box" style={{ marginTop: 16 }}>
        <div className="gh-box-header">自然语言命令</div>
        <div className="gh-box-body">
          <textarea
            className="gh-nl-input"
            placeholder={`例如：\n同步 ${currentRepo?.fullName || '当前仓库'} 的知识库\n创建分支 feature/demo\n提交 README：更新项目说明`}
            value={nlCommand}
            onChange={(e) => setNlCommand(e.target.value)}
          />
          <button
            type="button"
            className="gh-btn gh-btn-primary"
            style={{ marginTop: 8, width: '100%' }}
            disabled={loading === 'nl' || !nlCommand.trim()}
            onClick={handleNl}
          >
            {loading === 'nl' ? '执行中…' : '执行命令'}
          </button>
          {nlResult && (
            <Alert
              style={{ marginTop: 8 }}
              type="info"
              message={nlResult}
              showIcon
            />
          )}
        </div>
      </div>

      <Modal
        title={
          modal.type === 'branch'
            ? '创建分支'
            : modal.type === 'commit'
              ? '提交文件更改'
              : '创建 Pull Request'
        }
        open={modal.open}
        onCancel={() => setModal((m) => ({ ...m, open: false }))}
        onOk={handleModalOk}
        confirmLoading={!!loading}
        okText="执行"
      >
        {modal.type === 'branch' && (
          <Input
            placeholder="分支名，如 feature/quick-fix"
            value={form.branch}
            onChange={(e) => setForm((f) => ({ ...f, branch: e.target.value }))}
          />
        )}
        {modal.type === 'commit' && (
          <>
            <Input
              style={{ marginBottom: 8 }}
              placeholder="文件路径"
              value={form.path}
              onChange={(e) => setForm((f) => ({ ...f, path: e.target.value }))}
            />
            <Input
              style={{ marginBottom: 8 }}
              placeholder="提交说明"
              value={form.message}
              onChange={(e) => setForm((f) => ({ ...f, message: e.target.value }))}
            />
            <Input.TextArea
              rows={6}
              placeholder="文件内容"
              value={form.content}
              onChange={(e) => setForm((f) => ({ ...f, content: e.target.value }))}
            />
          </>
        )}
        {modal.type === 'pr' && (
          <>
            <Input
              style={{ marginBottom: 8 }}
              placeholder="源分支（head）"
              value={form.branch}
              onChange={(e) => setForm((f) => ({ ...f, branch: e.target.value }))}
            />
            <Input
              style={{ marginBottom: 8 }}
              placeholder="PR 标题"
              value={form.prTitle}
              onChange={(e) => setForm((f) => ({ ...f, prTitle: e.target.value }))}
            />
            <Input.TextArea
              rows={4}
              placeholder="PR 描述"
              value={form.prBody}
              onChange={(e) => setForm((f) => ({ ...f, prBody: e.target.value }))}
            />
          </>
        )}
      </Modal>
    </>
  )
}
