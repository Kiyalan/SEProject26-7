import { Input, Select, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useMemo, useState } from 'react'
import { syncTaskLogs } from '../../../../frontend/src/mock/adminData'
import type { SyncTaskLog } from '../../../../frontend/src/mock/adminData'

const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 'success', label: '成功' },
  { value: 'running', label: '进行中' },
  { value: 'failed', label: '失败' },
  { value: 'paused', label: '暂停' },
]

const triggerLabel: Record<SyncTaskLog['trigger'], string> = {
  manual: '手动',
  webhook: 'Webhook',
  scheduled: '定时',
}

const statusTag: Record<SyncTaskLog['status'], { color: string; label: string }> = {
  success: { color: 'green', label: '成功' },
  running: { color: 'blue', label: '进行中' },
  failed: { color: 'red', label: '失败' },
  paused: { color: 'orange', label: '暂停' },
}

export default function AdminSyncLogs() {
  const [status, setStatus] = useState('all')
  const [keyword, setKeyword] = useState('')

  const filtered = useMemo(() => {
    return syncTaskLogs.filter((row) => {
      const matchStatus = status === 'all' || row.status === status
      const matchKeyword =
        !keyword ||
        row.repoFullName.toLowerCase().includes(keyword.toLowerCase()) ||
        row.id.toLowerCase().includes(keyword.toLowerCase())
      return matchStatus && matchKeyword
    })
  }, [status, keyword])

  const columns: ColumnsType<SyncTaskLog> = [
    { title: '任务 ID', dataIndex: 'id', width: 100 },
    { title: '仓库', dataIndex: 'repoFullName' },
    {
      title: '触发方式',
      dataIndex: 'trigger',
      width: 100,
      render: (t: SyncTaskLog['trigger']) => triggerLabel[t],
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: SyncTaskLog['status']) => {
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
        <p>查看全平台仓库同步记录，支持按状态与仓库名筛选</p>
      </div>

      <div className="admin-toolbar">
        <Select
          value={status}
          onChange={setStatus}
          options={statusOptions}
          style={{ width: 140 }}
        />
        <Input
          placeholder="搜索仓库或任务 ID"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          allowClear
          style={{ width: 260 }}
        />
      </div>

      <Table
        className="admin-card"
        columns={columns}
        dataSource={filtered}
        rowKey="id"
        pagination={{ pageSize: 8, showTotal: (t) => `共 ${t} 条` }}
      />
    </div>
  )
}
