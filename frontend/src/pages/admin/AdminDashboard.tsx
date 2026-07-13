import { Card, Col, Progress, Row, Statistic, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { healthTrend, platformStats, syncTaskLogs } from '../../../../frontend/src/mock/adminData'
import type { SyncTaskLog } from '../../../../frontend/src/mock/adminData'

const statusTag: Record<SyncTaskLog['status'], { color: string; label: string }> = {
  success: { color: 'green', label: '成功' },
  running: { color: 'blue', label: '进行中' },
  failed: { color: 'red', label: '失败' },
  paused: { color: 'orange', label: '暂停' },
}

export default function AdminDashboard() {
  const recentLogs = syncTaskLogs.slice(0, 4)

  const columns: ColumnsType<SyncTaskLog> = [
    { title: '仓库', dataIndex: 'repoFullName', key: 'repo' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: SyncTaskLog['status']) => {
        const t = statusTag[s]
        return <Tag color={t.color}>{t.label}</Tag>
      },
    },
    { title: '开始时间', dataIndex: 'startedAt', key: 'startedAt', width: 200 },
    { title: '已同步文件', dataIndex: 'filesSynced', key: 'files', width: 100 },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>平台总览</h1>
        <p>全局仓库健康度与关键指标（对应 UC7 健康看板）</p>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic title="已绑定仓库" value={platformStats.totalRepos} suffix="个" />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title="同步成功率"
              value={platformStats.syncSuccessRate}
              suffix="%"
              valueStyle={{ color: '#1a7f37' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic title="知识库分块" value={platformStats.knowledgeChunks} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic title="活跃社区用户" value={platformStats.activeUsers} suffix="人" />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="同步健康度" className="admin-card">
            <div style={{ marginBottom: 16 }}>
              <div className="gh-data-row">
                <span className="gh-muted">成功同步</span>
                <span>{platformStats.syncedRepos} / {platformStats.totalRepos}</span>
              </div>
              <Progress
                percent={Math.round((platformStats.syncedRepos / platformStats.totalRepos) * 100)}
                status="active"
                strokeColor="#1a7f37"
              />
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">失败仓库</span>
              <Tag color="red">{platformStats.failedRepos} 个</Tag>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">FAQ 条目</span>
              <span>{platformStats.faqEntries}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">长期记忆条目</span>
              <span>{platformStats.memoryEntries}</span>
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">上次全量校验</span>
              <span>{platformStats.lastFullCheck}</span>
            </div>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="近 7 日趋势" className="admin-card">
            <Table
              size="small"
              pagination={false}
              dataSource={healthTrend}
              rowKey="date"
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '同步成功率 %', dataIndex: 'syncRate' },
                { title: '活跃用户', dataIndex: 'activeUsers' },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Card title="最近同步任务" style={{ marginTop: 16 }} className="admin-card">
        <Table size="small" columns={columns} dataSource={recentLogs} rowKey="id" pagination={false} />
      </Card>
    </div>
  )
}
