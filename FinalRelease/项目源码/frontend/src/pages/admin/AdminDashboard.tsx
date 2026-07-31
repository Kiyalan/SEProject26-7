import { Alert, Card, Col, Progress, Row, Statistic, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { fetchAdminOverview, type AdminSyncTask } from '../../api/generated'
import { adminClient } from '../../lib/AdminAxios'

const statusTag: Record<AdminSyncTask['status'], { color: string; label: string }> = {
  success: { color: 'green', label: '成功' },
  running: { color: 'blue', label: '进行中' },
  failed: { color: 'red', label: '失败' },
  paused: { color: 'orange', label: '已取消' },
}

export default function AdminDashboard() {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [stats, setStats] = useState({
    totalRepos: 0,
    syncedRepos: 0,
    failedRepos: 0,
    knowledgeChunks: 0,
    faqEntries: 0,
    activeUsers: 0,
    syncSuccessRate: 0,
    lastIndexedAt: '',
  })
  const [trend, setTrend] = useState<{ date: string; success: number; failed: number }[]>([])
  const [recentLogs, setRecentLogs] = useState<AdminSyncTask[]>([])

  useEffect(() => {
    setLoading(true)
    fetchAdminOverview({ client: adminClient })
      .then(({ data }) => {
        setStats(data.stats)
        setTrend(data.healthTrend)
        setRecentLogs(data.recentSyncTasks)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  const columns: ColumnsType<AdminSyncTask> = [
    { title: '仓库', dataIndex: 'repoFullName', key: 'repo' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: AdminSyncTask['status']) => {
        const t = statusTag[s]
        return <Tag color={t.color}>{t.label}</Tag>
      },
    },
    { title: '开始时间', dataIndex: 'startedAt', key: 'startedAt', width: 200 },
    { title: '已同步文件', dataIndex: 'filesSynced', key: 'files', width: 100 },
  ]

  const total = Math.max(stats.totalRepos, 1)

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>平台总览</h1>
        <p>全局仓库健康度与关键指标（对应 UC7 健康看板）</p>
      </div>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="已绑定仓库" value={stats.totalRepos} suffix="个" />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic
              title="同步成功率"
              value={stats.syncSuccessRate}
              suffix="%"
              valueStyle={{ color: '#1a7f37' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="知识库分块" value={stats.knowledgeChunks} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="FAQ 条目" value={stats.faqEntries} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="同步健康度" className="admin-card" loading={loading}>
            <div style={{ marginBottom: 16 }}>
              <div className="gh-data-row">
                <span className="gh-muted">成功同步</span>
                <span>
                  {stats.syncedRepos} / {stats.totalRepos}
                </span>
              </div>
              <Progress
                percent={Math.round((stats.syncedRepos / total) * 100)}
                status="active"
                strokeColor="#1a7f37"
              />
            </div>
            <div className="gh-data-row">
              <span className="gh-muted">失败任务</span>
              <span>{stats.failedRepos}</span>
            </div>
            {stats.lastIndexedAt ? (
              <div className="gh-data-row">
                <span className="gh-muted">最近索引</span>
                <span>{stats.lastIndexedAt}</span>
              </div>
            ) : null}
            {trend.length > 0 && (
              <div style={{ marginTop: 12 }}>
                <strong style={{ fontSize: 13 }}>近 7 日趋势</strong>
                {trend.map((point) => (
                  <div key={point.date} className="gh-data-row" style={{ fontSize: 12 }}>
                    <span className="gh-muted">{point.date}</span>
                    <span>
                      成功 {point.success} · 失败 {point.failed}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="最近同步任务" className="admin-card" loading={loading}>
            <Table
              rowKey="id"
              columns={columns}
              dataSource={recentLogs}
              pagination={false}
              size="small"
              locale={{ emptyText: '暂无任务记录，请先在知识库页构建索引' }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}
