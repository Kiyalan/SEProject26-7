import { Alert, Button, Checkbox, Radio, Table, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import { faqRepoOptions } from '../../mock/adminData'
import type { FaqRepoOption } from '../../mock/adminData'

export default function AdminFaqExport() {
  const [selected, setSelected] = useState<string[]>(faqRepoOptions.map((r) => r.repoFullName))
  const [format, setFormat] = useState<'markdown' | 'json'>('markdown')
  const [exporting, setExporting] = useState(false)

  const columns: ColumnsType<FaqRepoOption> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    { title: 'FAQ 条目', dataIndex: 'faqCount', width: 100 },
    { title: '记忆条目', dataIndex: 'memoryCount', width: 100 },
    { title: '最近更新', dataIndex: 'lastUpdated', width: 200 },
  ]

  const toggleAll = (checked: boolean) => {
    setSelected(checked ? faqRepoOptions.map((r) => r.repoFullName) : [])
  }

  const exportFaq = () => {
    if (!selected.length) {
      message.warning('请至少选择一个仓库')
      return
    }
    setExporting(true)
    setTimeout(() => {
      const content =
        format === 'markdown'
          ? `# RepoPilot FAQ 导出\n\n${selected.map((r) => `## ${r}\n- 演示 FAQ 内容\n`).join('\n')}`
          : JSON.stringify({ repos: selected, exportedAt: new Date().toISOString() }, null, 2)

      const blob = new Blob([content], {
        type: format === 'markdown' ? 'text/markdown' : 'application/json',
      })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `repopilot-faq-export.${format === 'markdown' ? 'md' : 'json'}`
      a.click()
      URL.revokeObjectURL(url)

      setExporting(false)
      message.success(`已导出 ${selected.length} 个仓库的 FAQ（演示文件）`)
    }, 800)
  }

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>FAQ 批量导出</h1>
        <p>选择仓库并导出统一 FAQ 文档，支持 Markdown / JSON 格式</p>
      </div>

      <Alert
        type="info"
        showIcon
        message="导出内容为演示数据"
        description="正式环境将从各仓库长期记忆与 FAQ 库聚合生成文档。"
        style={{ marginBottom: 16 }}
      />

      <div className="admin-toolbar">
        <Checkbox
          checked={selected.length === faqRepoOptions.length}
          indeterminate={selected.length > 0 && selected.length < faqRepoOptions.length}
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
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys as string[]),
        }}
        columns={columns}
        dataSource={faqRepoOptions}
        rowKey="repoFullName"
        pagination={false}
      />
    </div>
  )
}
