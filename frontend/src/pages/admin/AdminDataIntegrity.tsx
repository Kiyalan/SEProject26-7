import { Alert, Button, Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useState } from 'react'
import { fetchAdminIntegrity, type AdminIntegrityCheck } from '../../api/generated'
import { adminClient } from '../../lib/AdminAxios'

export default function AdminDataIntegrity() {
  const [rows, setRows] = useState<AdminIntegrityCheck[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    fetchAdminIntegrity({ client: adminClient, query: { limit: 200 } })
      .then(({ data }) => {
        setRows(data.items)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const columns: ColumnsType<AdminIntegrityCheck> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    {
      title: '知识库',
      dataIndex: 'knowledgeOk',
      width: 90,
      render: (ok: boolean) => <Tag color={ok ? 'green' : 'red'}>{ok ? '正常' : '异常'}</Tag>,
    },
    {
      title: 'FAQ',
      dataIndex: 'faqOk',
      width: 90,
      render: (ok: boolean) => <Tag color={ok ? 'green' : 'orange'}>{ok ? '有' : '无'}</Tag>,
    },
    { title: 'Chunks', dataIndex: 'chunkCount', width: 90 },
    { title: '最近检查', dataIndex: 'lastChecked', width: 180 },
    {
      title: '问题',
      dataIndex: 'issues',
      render: (issues: string[]) => (issues.length ? issues.join('；') : '—'),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>数据完整性</h1>
        <p>按仓库检查知识库就绪状态与 FAQ 覆盖情况</p>
      </div>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <div className="admin-toolbar">
        <Button
          type="primary"
          loading={loading}
          onClick={() => {
            load()
            message.success('已刷新完整性检查')
          }}
        >
          重新检查
        </Button>
      </div>
      <Table
        className="admin-card"
        rowKey={(r) => r.repoId || r.repoFullName}
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{ pageSize: 10 }}
      />
    </div>
  )
}
