import { Alert, Button, Checkbox, Radio, Table, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import {
  exportAdminFaq,
  fetchAdminFaqRepos,
  type AdminFaqRepoOption,
} from '../../api/generated'
import { adminClient } from '../../lib/AdminAxios'

export default function AdminFaqExport() {
  const [repos, setRepos] = useState<AdminFaqRepoOption[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [format, setFormat] = useState<'markdown' | 'json'>('markdown')
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    fetchAdminFaqRepos({ client: adminClient })
      .then(({ data }) => {
        setRepos(data.items)
        setSelected(data.items.map((r) => r.repoId))
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  const columns: ColumnsType<AdminFaqRepoOption> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    { title: 'FAQ 条目', dataIndex: 'faqCount', width: 100 },
    { title: '记忆条目', dataIndex: 'memoryCount', width: 100 },
    { title: '最近更新', dataIndex: 'lastUpdated', width: 200, render: (v) => v || '—' },
  ]

  const toggleAll = (checked: boolean) => {
    setSelected(checked ? repos.map((r) => r.repoId) : [])
  }

  const exportFaq = async () => {
    if (!selected.length) {
      message.warning('请至少选择一个仓库')
      return
    }
    setExporting(true)
    try {
      const { data } = await exportAdminFaq({
        client: adminClient,
        body: { repoIds: selected, format },
      })
      const blob = new Blob([data.content], {
        type: format === 'markdown' ? 'text/markdown' : 'application/json',
      })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `repopilot-faq-export.${format === 'markdown' ? 'md' : 'json'}`
      a.click()
      URL.revokeObjectURL(url)
      message.success(`已导出 ${data.repoCount} 个仓库、${data.itemCount} 条 FAQ`)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '导出失败')
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>FAQ 批量导出</h1>
        <p>选择仓库并导出统一 FAQ 文档，支持 Markdown / JSON 格式</p>
      </div>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}

      <Alert
        type="info"
        showIcon
        message="数据来自各仓库 GraphRAG FAQ 聚类结果"
        description="请先在知识库页生成 FAQ；此处聚合 repo_faq_items 真实数据。"
        style={{ marginBottom: 16 }}
      />

      <div className="admin-toolbar">
        <Checkbox
          checked={selected.length > 0 && selected.length === repos.length}
          indeterminate={selected.length > 0 && selected.length < repos.length}
          onChange={(e) => toggleAll(e.target.checked)}
        >
          全选仓库
        </Checkbox>
        <Radio.Group value={format} onChange={(e) => setFormat(e.target.value)}>
          <Radio.Button value="markdown">Markdown</Radio.Button>
          <Radio.Button value="json">JSON</Radio.Button>
        </Radio.Group>
        <Button type="primary" loading={exporting} onClick={exportFaq}>
          导出 FAQ 文档
        </Button>
      </div>

      <Table
        className="admin-card"
        loading={loading}
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys as string[]),
        }}
        columns={columns}
        dataSource={repos}
        rowKey="repoId"
        pagination={false}
        locale={{ emptyText: '暂无已索引仓库' }}
      />
    </div>
  )
}
