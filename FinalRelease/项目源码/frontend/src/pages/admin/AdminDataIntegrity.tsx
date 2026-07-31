import { Alert, Button, Popconfirm, Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useState } from 'react'
import { fetchAdminIntegrity, type AdminIntegrityCheck } from '../../api/generated'
import { adminAxios, adminClient } from '../../lib/AdminAxios'

export default function AdminDataIntegrity() {
  const [rows, setRows] = useState<AdminIntegrityCheck[]>([])
  const [loading, setLoading] = useState(false)
  const [resetting, setResetting] = useState<string | null>(null)
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

  const resetKnowledge = async (repoId: string | undefined, repoFullName: string) => {
    if (!repoId) {
      message.error('缺少仓库 ID，无法重置')
      return
    }
    setResetting(repoId)
    try {
      await adminAxios.delete(`/api/admin/repos/${encodeURIComponent(repoId)}/knowledge`)
      message.success(`已重置 ${repoFullName} 的知识库索引`)
      load()
    } catch (err) {
      message.error(err instanceof Error ? err.message : '重置失败')
    } finally {
      setResetting(null)
    }
  }

  const columns: ColumnsType<AdminIntegrityCheck> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    {
      title: '知识库',
      dataIndex: 'knowledgeOk',
      width: 90,
      render: (ok: boolean) => <Tag color={ok ? 'green' : 'red'}>{ok ? '就绪' : '未就绪'}</Tag>,
    },
    {
      title: 'FAQ',
      dataIndex: 'faqOk',
      width: 90,
      render: (ok: boolean) => <Tag color={ok ? 'green' : 'orange'}>{ok ? '有' : '无'}</Tag>,
    },
    { title: 'Chunks', dataIndex: 'chunkCount', width: 90 },
    { title: '最近索引', dataIndex: 'lastChecked', width: 180, render: (v) => v || '—' },
    {
      title: '问题',
      dataIndex: 'issues',
      render: (issues: string[]) => (issues.length ? issues.join('；') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_, row) => (
        <Popconfirm
          title="重置知识库？"
          description="将清除该仓库的 CodeWiki 图谱与本地索引，需重新构建。"
          okText="重置"
          cancelText="取消"
          okButtonProps={{ danger: true }}
          onConfirm={() => resetKnowledge(row.repoId, row.repoFullName)}
        >
          <Button
            size="small"
            danger
            loading={resetting === row.repoId}
            disabled={!row.repoId}
          >
            重置
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>数据完整性</h1>
        <p>基于 repo_index 就绪状态与 FAQ 条目数（非深度校验）</p>
      </div>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <div className="admin-toolbar">
        <Button type="primary" loading={loading} onClick={load}>
          刷新
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
