import { Alert, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { fetchAdminSyncFailures, type AdminSyncFailure } from '../../api/generated'
import { adminClient } from '../../lib/AdminAxios'

const errorTypeLabel: Record<AdminSyncFailure['errorType'], string> = {
  network: '网络',
  auth: '鉴权',
  rate_limit: '限流',
  webhook: 'Webhook',
  parse: '其他',
}

export default function AdminSyncFailures() {
  const [rows, setRows] = useState<AdminSyncFailure[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    fetchAdminSyncFailures({ client: adminClient, query: { limit: 100 } })
      .then(({ data }) => {
        setRows(data.items)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  const columns: ColumnsType<AdminSyncFailure> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    { title: '失败时间', dataIndex: 'failedAt', width: 190 },
    {
      title: '归类',
      dataIndex: 'errorType',
      width: 90,
      render: (t: AdminSyncFailure['errorType']) => <Tag>{errorTypeLabel[t] || t}</Tag>,
    },
    { title: '错误信息', dataIndex: 'errorMessage', ellipsis: true },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>同步失败排查</h1>
        <p>失败的知识库构建任务（只读；请到知识库页重新构建）</p>
      </div>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Table
        className="admin-card"
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无失败任务' }}
      />
    </div>
  )
}
