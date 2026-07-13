import { Button, Select, Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useMemo, useState } from 'react'
import { syncFailures } from '../../mock/adminData'
import type { SyncFailure } from '../../mock/adminData'

const errorTypeLabel: Record<SyncFailure['errorType'], string> = {
  network: '网络异常',
  auth: '授权失效',
  rate_limit: 'API 限流',
  webhook: 'Webhook',
  parse: '解析错误',
}

const statusTag: Record<SyncFailure['status'], { color: string; label: string }> = {
  pending: { color: 'orange', label: '待处理' },
  retrying: { color: 'blue', label: '重试中' },
  ignored: { color: 'default', label: '已忽略' },
}

export default function AdminSyncFailures() {
  const [rows, setRows] = useState(syncFailures)
  const [filterType, setFilterType] = useState('all')

  const filtered = useMemo(() => {
    if (filterType === 'all') return rows
    return rows.filter((r) => r.errorType === filterType)
  }, [rows, filterType])

  const retry = (id: string) => {
    setRows((prev) =>
      prev.map((r) =>
        r.id === id ? { ...r, status: 'retrying' as const, retryCount: r.retryCount + 1 } : r,
      ),
    )
    message.loading({ content: '正在重试同步…', key: id, duration: 1.2 })
    setTimeout(() => {
      message.success({ content: '已提交重试任务（演示）', key: id })
    }, 1200)
  }

  const ignore = (id: string) => {
    setRows((prev) =>
      prev.map((r) => (r.id === id ? { ...r, status: 'ignored' as const } : r)),
    )
    message.info('已标记为忽略')
  }

  const columns: ColumnsType<SyncFailure> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    {
      title: '故障类型',
      dataIndex: 'errorType',
      width: 110,
      render: (t: SyncFailure['errorType']) => errorTypeLabel[t],
    },
    { title: '失败时间', dataIndex: 'failedAt', width: 190 },
    { title: '错误信息', dataIndex: 'errorMessage', ellipsis: true },
    { title: '重试次数', dataIndex: 'retryCount', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: SyncFailure['status']) => {
        const t = statusTag[s]
        return <Tag color={t.color}>{t.label}</Tag>
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_, record) => (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            type="link"
            disabled={record.status === 'ignored'}
            onClick={() => retry(record.id)}
          >
            重试
          </Button>
          <Button
            size="small"
            type="link"
            danger
            disabled={record.status === 'ignored'}
            onClick={() => ignore(record.id)}
          >
            忽略
          </Button>
        </div>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>同步故障排查</h1>
        <p>检索同步失败仓库，查看原因并执行重试或忽略（演示交互）</p>
      </div>

      <div className="admin-toolbar">
        <Select
          value={filterType}
          onChange={setFilterType}
          style={{ width: 160 }}
          options={[
            { value: 'all', label: '全部类型' },
            { value: 'network', label: '网络异常' },
            { value: 'auth', label: '授权失效' },
            { value: 'rate_limit', label: 'API 限流' },
            { value: 'webhook', label: 'Webhook' },
            { value: 'parse', label: '解析错误' },
          ]}
        />
      </div>

      <Table
        className="admin-card"
        columns={columns}
        dataSource={filtered}
        rowKey="id"
        pagination={{ pageSize: 8 }}
      />
    </div>
  )
}
