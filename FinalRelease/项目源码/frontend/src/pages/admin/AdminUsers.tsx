import { Alert, Button, Card, Col, Form, Input, Modal, Row, Select, Space, Statistic, Table, Tag, Tooltip } from 'antd'
import { CheckCircleOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { adminAxios } from '../../lib/AdminAxios'

interface UserRecord {
  id: number
  login: string
  username: string
  email: string
  role: string
  status: string
  avatarUrl: string
  githubLogin: string
  boundRepos: number
  buildTasksCompleted: number
  buildTasksFailed: number
  createdAt: string
  lastLogin: string
  lastActive: string
}

interface GlobalStats {
  totalUsers: number
  activeUsers: number
  disabledUsers: number
  adminCount: number
  totalRepos: number
  totalBuildTasks: number
  activeUsers7d: number
}

export default function AdminUsers() {
  const [rows, setRows] = useState<UserRecord[]>([])
  const [stats, setStats] = useState<GlobalStats | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // modal state
  const [modalOpen, setModalOpen] = useState(false)
  const [editingUser, setEditingUser] = useState<UserRecord | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const loadAll = () => {
    setLoading(true)
    Promise.all([
      adminAxios.get('/api/admin/users').then(r => r.data),
      adminAxios.get('/api/admin/users/stats').then(r => r.data),
    ])
      .then(([usersData, statsData]) => {
        setRows((usersData.items ?? []) as UserRecord[])
        setStats(statsData as GlobalStats)
        setMessage(usersData.message ?? null)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { loadAll() }, [])

  const openCreate = () => {
    setEditingUser(null)
    form.resetFields()
    form.setFieldsValue({ role: 'user' })
    setModalOpen(true)
  }

  const openEdit = (user: UserRecord) => {
    setEditingUser(user)
    form.setFieldsValue({
      username: user.username || user.login,
      email: user.email,
      role: user.role,
      status: user.status,
    })
    setModalOpen(true)
  }

  const handleSave = async () => {
    try {
      const values = await form.validateFields()
      setSaving(true)
      if (editingUser) {
        const payload: Record<string, string> = {}
        if (values.email !== undefined) payload.email = values.email
        if (values.role) payload.role = values.role
        if (values.status) payload.status = values.status
        if (values.password) payload.password = values.password
        await adminAxios.put(`/api/admin/users/${editingUser.id}`, payload)
      } else {
        await adminAxios.post('/api/admin/users', {
          username: values.username,
          password: values.password,
          email: values.email || '',
          role: values.role || 'user',
        })
      }
      setModalOpen(false)
      loadAll()
    } catch (err) {
      if (err instanceof Error && err.message) setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = (user: UserRecord) => {
    Modal.confirm({
      title: '确认删除',
      content: `确定要删除用户「${user.login}」吗？此操作不可撤销。`,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await adminAxios.delete(`/api/admin/users/${user.id}`)
          loadAll()
        } catch (err) {
          setError(err instanceof Error ? err.message : '删除失败')
        }
      },
    })
  }

  const handleBan = (user: UserRecord) => {
    Modal.confirm({
      title: '确认封禁',
      content: `确定要封禁用户「${user.login}」吗？封禁后该用户将无法登录和使用应用。`,
      okText: '封禁',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await adminAxios.post(`/api/admin/users/${user.id}/ban`)
          loadAll()
        } catch (err) {
          setError(err instanceof Error ? err.message : '封禁失败')
        }
      },
    })
  }

  const handleUnban = async (user: UserRecord) => {
    try {
      await adminAxios.post(`/api/admin/users/${user.id}/unban`)
      loadAll()
    } catch (err) {
      setError(err instanceof Error ? err.message : '解禁失败')
    }
  }

  const roleLabel = (role: string) => {
    switch (role) {
      case 'admin': return '管理员'
      case 'viewer': return '只读'
      default: return '普通用户'
    }
  }

  const columns: ColumnsType<UserRecord> = [
    {
      title: '用户',
      dataIndex: 'login',
      width: 160,
      render: (name: string, record: UserRecord) => (
        <Space>
          {record.avatarUrl ? (
            <img src={record.avatarUrl} alt="" style={{ width: 24, height: 24, borderRadius: '50%' }} />
          ) : (
            <UserOutlined style={{ fontSize: 18, color: '#999' }} />
          )}
          <span>
            {name}
            {record.githubLogin && <Tag style={{ marginLeft: 4 }} color="blue">GitHub</Tag>}
          </span>
        </Space>
      ),
    },
    { title: '邮箱', dataIndex: 'email', width: 180, render: (v: string) => v || '-' },
    {
      title: '角色',
      dataIndex: 'role',
      width: 90,
      render: (r: string) => (
        <Tag color={r === 'admin' ? 'blue' : r === 'viewer' ? 'default' : 'green'}>
          {roleLabel(r)}
        </Tag>
      ),
    },
    {
      title: '仓库',
      dataIndex: 'boundRepos',
      width: 70,
      align: 'center',
    },
    {
      title: '构建',
      key: 'builds',
      width: 100,
      align: 'center',
      render: (_, record: UserRecord) => (
        <Tooltip title={`成功: ${record.buildTasksCompleted} / 失败: ${record.buildTasksFailed}`}>
          <Space size={4}>
            <Tag color="green" style={{ margin: 0 }}>{record.buildTasksCompleted ?? 0}</Tag>
            <span style={{ color: '#ccc' }}>/</span>
            <Tag color="red" style={{ margin: 0 }}>{record.buildTasksFailed ?? 0}</Tag>
          </Space>
        </Tooltip>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (s: string) => (
        <Tag color={s === 'active' ? 'green' : 'red'}>{s === 'active' ? '启用' : '禁用'}</Tag>
      ),
    },
    {
      title: '最近活跃',
      dataIndex: 'lastActive',
      width: 150,
      render: (v: string) => v || '-',
    },
    {
      title: '操作',
      width: 180,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(record)}>编辑</Button>
          {record.status === 'active' ? (
            <Button type="link" size="small" danger onClick={() => handleBan(record)}>封禁</Button>
          ) : (
            <Button type="link" size="small" onClick={() => handleUnban(record)}
              icon={<CheckCircleOutlined />}>解禁</Button>
          )}
          <Button type="link" size="small" danger onClick={() => handleDelete(record)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>用户管理</h1>
        <p>统一管理平台用户，通过 GitHub 登录的用户自动注册。可查看使用统计、封禁/解禁用户。</p>
      </div>

      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} closable onClose={() => setError(null)} />}
      {message && <Alert type="info" showIcon message={message} style={{ marginBottom: 16 }} />}

      {/* 全局统计卡片 */}
      {stats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small" hoverable>
              <Statistic title="总用户" value={stats.totalUsers} prefix={<TeamOutlined />} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" hoverable>
              <Statistic title="活跃用户" value={stats.activeUsers} valueStyle={{ color: '#52c41a' }} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" hoverable>
              <Statistic title="被禁用" value={stats.disabledUsers} valueStyle={{ color: '#ff4d4f' }} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" hoverable>
              <Statistic title="管理员" value={stats.adminCount} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" hoverable>
              <Statistic title="总仓库" value={stats.totalRepos} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small" hoverable>
              <Statistic title="7日活跃" value={stats.activeUsers7d} />
            </Card>
          </Col>
        </Row>
      )}

      <div style={{ marginBottom: 16, textAlign: 'right' }}>
        <Button type="primary" onClick={openCreate}>新建用户</Button>
      </div>
      <Table
        className="admin-card"
        loading={loading}
        columns={columns}
        dataSource={rows}
        rowKey="id"
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无用户数据 — 用户通过 GitHub 登录后自动出现在此列表中' }}
        scroll={{ x: 1050 }}
      />

      <Modal
        title={editingUser ? '编辑用户' : '新建用户'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          {!editingUser && (
            <>
              <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
                <Input placeholder="登录用户名" />
              </Form.Item>
              <Form.Item name="password" label="密码" rules={[{ required: true, min: 6, message: '密码至少 6 位' }]}>
                <Input.Password placeholder="至少 6 位" />
              </Form.Item>
            </>
          )}
          {editingUser && (
            <Form.Item name="password" label="新密码（留空不修改）">
              <Input.Password placeholder="留空则不修改密码" />
            </Form.Item>
          )}
          <Form.Item name="email" label="邮箱">
            <Input placeholder="选填" />
          </Form.Item>
          <Form.Item name="role" label="角色">
            <Select
              options={[
                { label: '普通用户', value: 'user' },
                { label: '管理员', value: 'admin' },
                { label: '只读', value: 'viewer' },
              ]}
            />
          </Form.Item>
          {editingUser && (
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { label: '启用', value: 'active' },
                  { label: '禁用', value: 'disabled' },
                ]}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  )
}
