import { Alert, Button, Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import { integrityChecks } from '../../../../frontend/src/mock/adminData'
import type { IntegrityCheck } from '../../../../frontend/src/mock/adminData'

function BoolTag({ ok }: { ok: boolean }) {
  return <Tag color={ok ? 'green' : 'red'}>{ok ? '正常' : '异常'}</Tag>
}

export default function AdminDataIntegrity() {
  const [checking, setChecking] = useState(false)
  const [lastRun, setLastRun] = useState('2026-07-12T14:30:00+08:00')

  const runCheck = () => {
    setChecking(true)
    setTimeout(() => {
      setChecking(false)
      setLastRun(new Date().toISOString())
      message.success('全平台数据完整性校验已完成（演示）')
    }, 1200)
  }

  const columns: ColumnsType<IntegrityCheck> = [
    { title: '仓库', dataIndex: 'repoFullName' },
    {
      title: '知识库',
      dataIndex: 'knowledgeOk',
      width: 90,
      render: (v: boolean) => <BoolTag ok={v} />,
    },
    {
      title: '记忆库',
      dataIndex: 'memoryOk',
      width: 90,
      render: (v: boolean) => <BoolTag ok={v} />,
    },
    {
      title: 'FAQ 库',
      dataIndex: 'faqOk',
      width: 90,
      render: (v: boolean) => <BoolTag ok={v} />,
    },
    { title: '分块数', dataIndex: 'chunkCount', width: 80 },
    { title: '记忆条目', dataIndex: 'memoryCount', width: 90 },
    {
      title: '问题',
      dataIndex: 'issues',
      render: (issues: string[]) =>
        issues.length ? (
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12 }}>
            {issues.map((i) => (
              <li key={i}>{i}</li>
            ))}
          </ul>
        ) : (
          <span className="gh-muted">无</span>
        ),
    },
  ]

  const problemCount = integrityChecks.filter(
    (r) => !r.knowledgeOk || !r.memoryOk || !r.faqOk,
  ).length

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>数据完整性校验</h1>
        <p>校验各仓库知识库、长期记忆库与 FAQ 库的数据一致性</p>
      </div>

      <Alert
        type={problemCount ? 'warning' : 'success'}
        showIcon
        message={
          problemCount
            ? `发现 ${problemCount} 个仓库存在数据异常，请查看下表详情`
            : '当前抽样仓库数据完整性良好'
        }
        style={{ marginBottom: 16 }}
      />

      <div className="admin-toolbar">
        <Button type="primary" loading={checking} onClick={runCheck}>
          执行全平台校验
        </Button>
        <span className="gh-muted" style={{ fontSize: 13 }}>
          上次校验：{lastRun}
        </span>
      </div>

      <Table
        className="admin-card"
        columns={columns}
        dataSource={integrityChecks}
        rowKey="repoFullName"
        pagination={false}
      />
    </div>
  )
}
