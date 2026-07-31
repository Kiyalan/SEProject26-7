import { Alert, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { fetchAdminAuditLogs, type AdminAuditLog } from '../../api/generated'
import { adminClient } from '../../lib/AdminAxios'

export default function AdminAuditLogs() {
  const [rows, setRows] = useState<AdminAuditLog[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    fetchAdminAuditLogs({ client: adminClient, query: { limit: 200 } })
      .then(({ data }) => {
        setRows(data.items)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  const columns: ColumnsType<AdminAuditLog> = [
    { title: '时间', dataIndex: 'createdAt', width: 200 },
    { title: '管理员', dataIndex: 'admin', width: 120 },
    { title: '操作', dataIndex: 'action' },
    { title: '目标', dataIndex: 'target' },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (r: AdminAuditLog['result']) => (
        <Tag color={r === 'success' ? 'green' : 'red'}>{r === 'success' ? '成功' : '失败'}</Tag>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>运维操作日志</h1>
        <p>记录管理员在后台的关键操作，便于审计与追溯</p>
      </div>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Table
        className="admin-card"
        loading={loading}
        columns={columns}
        dataSource={rows}
        rowKey="id"
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: '暂无审计记录' }}
      />
    </div>
  )
}
