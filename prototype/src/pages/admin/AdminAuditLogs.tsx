import { Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { auditLogs } from '../../mock/adminData'
import type { AuditLog } from '../../mock/adminData'

export default function AdminAuditLogs() {
  const columns: ColumnsType<AuditLog> = [
    { title: '时间', dataIndex: 'createdAt', width: 200 },
    { title: '管理员', dataIndex: 'admin', width: 120 },
    { title: '操作', dataIndex: 'action' },
    { title: '目标', dataIndex: 'target' },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (r: AuditLog['result']) => (
        <Tag color={r === 'success' ? 'green' : 'red'}>
          {r === 'success' ? '成功' : '失败'}
        </Tag>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>运维操作日志</h1>
        <p>记录管理员在后台的关键操作，便于审计与追溯</p>
      </div>

      <Table
        className="admin-card"
        columns={columns}
        dataSource={auditLogs}
        rowKey="id"
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
      />
    </div>
  )
}
