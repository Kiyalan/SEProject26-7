import { Alert, Input, Select, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { fetchAdminSyncTasks, type AdminSyncTask } from '../../api/generated'
import { adminClient } from '../../lib/AdminAxios'

const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 'success', label: '成功' },
  { value: 'running', label: '进行中' },
  { value: 'failed', label: '失败' },
  { value: 'paused', label: '暂停' },
]

const triggerLabel: Record<AdminSyncTask['trigger'], string> = {
  manual: '手动',
  webhook: 'Webhook',
  scheduled: '定时',
}

const statusTag: Record<AdminSyncTask['status'], { color: string; label: string }> = {
  success: { color: 'green', label: '成功' },
  running: { color: 'blue', label: '进行中' },
  failed: { color: 'red', label: '失败' },
  paused: { color: 'orange', label: '暂停' },
}

export default function AdminSyncLogs() {
  const [status, setStatus] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [rows, setRows] = useState<AdminSyncTask[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    fetchAdminSyncTasks({
      client: adminClient,
      query: { status, keyword: keyword || undefined, limit: 100 },
    })
      .then(({ data }) => {
        setRows(data.items)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [status, keyword])

  const columns: ColumnsType<AdminSyncTask> = [
    { title: '任务 ID', dataIndex: 'id', width: 120, ellipsis: true },
    { title: '仓库', dataIndex: 'repoFullName' },
    {
      title: '触发方式',
      dataIndex: 'trigger',
      width: 100,
      render: (t: AdminSyncTask['trigger']) => triggerLabel[t],
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: AdminSyncTask['status']) => {
        const t = statusTag[s]
        return <Tag color={t.color}>{t.label}</Tag>
      },
    },
    { title: '开始', dataIndex: 'startedAt', width: 190 },
    { title: '结束', dataIndex: 'endedAt', width: 190, render: (v) => v || '—' },
    { title: '文件数', dataIndex: 'filesSynced', width: 80 },
    {
      title: '错误信息',
      dataIndex: 'errorMessage',
      ellipsis: true,
      render: (v) => v || '—',
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>同步任务日志</h1>
        <p>查看全平台知识库构建/同步记录（来自 knowledge_build_tasks）</p>
      </div>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}

      <div className="admin-toolbar">
        <Select value={status} onChange={setStatus} options={statusOptions} style={{ width: 140 }} />
        <Input.Search
          allowClear
          placeholder="按仓库名 / 任务 ID 筛选"
          onSearch={setKeyword}
          onChange={(e) => {
            if (!e.target.value) setKeyword('')
          }}
          style={{ width: 260 }}
        />
      </div>

      <Table
        className="admin-card"
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
      />
    </div>
  )
}
