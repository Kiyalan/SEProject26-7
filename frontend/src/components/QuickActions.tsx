import { Alert, Modal, Input, message } from 'antd'
import { useState } from 'react'
import { useRepoContext } from '../context/RepoContext'
import {
  executeGitAction,
  executeNlCommand,
} from '../api/generated'

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
    base: 'main',
    path: 'README.md',
    content: '',
    message: '',
    prTitle: '',
    prBody: '',
  })

  const run = async (key: string, fn: () => Promise<unknown>, successText = '操作成功') => {
    if (!currentRepoId) {
      message.warning('请先选择仓库')
      return
    }
    setLoading(key)
    try {
      const result = await fn()
      message.success(successText)
      return result
    } catch (err) {
      message.error(err instanceof Error ? err.message : '操作失败')
    } finally {
      setLoading(null)
    }
  }

  const handleSync = () =>
    run(
      'sync',
      async () => {
        const { data } = await executeGitAction({
          path: { repoId: currentRepoId! },
          body: { action: 'sync_knowledge', params: {} },
        })
        const taskId = typeof data?.taskId === 'string' ? data.taskId : ''
        await syncRepoList()
        if (taskId) {
          message.info(`任务 ${taskId.slice(0, 8)}… 已排队，可在知识库页查看日志`)
        }
      },
      '已通过 sync_knowledge 提交知识库同步',
    )

  const handleNl = async () => {
    if (!nlCommand.trim() || !currentRepoId) return
    setLoading('nl')
    setNlResult(null)
    try {
      const { data: result } = await executeNlCommand({
        path: { repoId: currentRepoId },
        body: { command: nlCommand.trim() },
      })
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
      let detail = '操作成功'
      if (modal.type === 'branch') {
        const { data } = await executeGitAction({
          path: { repoId: currentRepoId },
          body: { action: 'create_branch', params: { branch: form.branch } },
        })
        detail = typeof data?.message === 'string' ? data.message : `分支 ${form.branch} 已创建`
      } else if (modal.type === 'commit') {
        const { data } = await executeGitAction({
          path: { repoId: currentRepoId },
          body: {
            action: 'commit_file',
            params: {
              path: form.path,
              content: form.content,
              message: form.message || `Update ${form.path}`,
            },
          },
        })
        detail = typeof data?.message === 'string' ? data.message : `已提交 ${form.path}`
      } else {
        const head = form.branch.trim()
        const base = (form.base || 'main').trim()
        if (!head) {
          message.warning('请填写 compare（源分支 / head）')
          return
        }
        if (!base) {
          message.warning('请填写 base（目标分支）')
          return
        }
        if (head === base) {
          message.warning('base 与 compare 不能相同')
          return
        }
        const { data } = await executeGitAction({
          path: { repoId: currentRepoId },
          body: {
            action: 'create_pr',
            params: {
              title: form.prTitle || `Merge ${head} into ${base}`,
              body: form.prBody || `Merge \`${head}\` → \`${base}\` via RepoPilot.`,
              head,
              base,
            },
          },
        })
        const url = typeof data?.htmlUrl === 'string' ? data.htmlUrl : typeof data?.url === 'string' ? data.url : ''
        detail = url
          ? `PR 已创建（${head} → ${base}）：${url}`
          : typeof data?.message === 'string'
            ? data.message
            : `Pull Request 已创建（${head} → ${base}）`
      }
      message.success(detail)
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
            placeholder={`例如：\n同步知识库\n创建分支 feature/demo\n提交 README.md：更新项目说明\n创建 PR from feature/demo into main`}
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
            <p className="gh-muted" style={{ marginBottom: 8, fontSize: 12 }}>
              通过 GitHub Contents API，在默认分支（或你指定的分支）上新建/覆盖单个文件并生成一次 commit。
              适合改 README 等文本文件；不是本地 git add/commit/push 全仓库。
            </p>
            <Input
              style={{ marginBottom: 8 }}
              placeholder="文件路径，如 README.md 或 docs/note.md"
              value={form.path}
              onChange={(e) => setForm((f) => ({ ...f, path: e.target.value }))}
            />
            <Input
              style={{ marginBottom: 8 }}
              placeholder="提交说明（commit message）"
              value={form.message}
              onChange={(e) => setForm((f) => ({ ...f, message: e.target.value }))}
            />
            <Input.TextArea
              rows={8}
              placeholder="文件完整内容（将写入上述路径）"
              value={form.content}
              onChange={(e) => setForm((f) => ({ ...f, content: e.target.value }))}
            />
          </>
        )}
        {modal.type === 'pr' && (
          <>
            <p className="gh-muted" style={{ marginBottom: 8, fontSize: 12 }}>
              明确双方：compare（源 / head）合并进 base（目标）。对应 GitHub 的
              {' '}
              <code>base...compare</code>
              。
            </p>
            <Input
              style={{ marginBottom: 8 }}
              addonBefore="base"
              placeholder="目标分支，如 main"
              value={form.base}
              onChange={(e) => setForm((f) => ({ ...f, base: e.target.value }))}
            />
            <Input
              style={{ marginBottom: 8 }}
              addonBefore="compare"
              placeholder="源分支（head），如 feature/demo"
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
              placeholder="PR 描述（可选）"
              value={form.prBody}
              onChange={(e) => setForm((f) => ({ ...f, prBody: e.target.value }))}
            />
          </>
        )}
      </Modal>
    </>
  )
}
