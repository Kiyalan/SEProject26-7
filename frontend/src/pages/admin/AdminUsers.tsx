import { Alert, Button, Form, Input, Modal, Select, Space, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { fetchAdminUsers } from '../../api/generated'
import { adminAxios, adminClient } from '../../lib/AdminAxios'

interface UserRecord {
  id: number
  login: string
  username: string
  email: string
  role: string
  status: string
  boundRepos: number
  createdAt: string
  lastLogin: string
}

export default function AdminUsers() {
  const [rows, setRows] = useState<UserRecord[]>([])
  const [message, setMessage] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // modal state
  const [modalOpen, setModalOpen] = useState(false)
  const [editingUser, setEditingUser] = useState<UserRecord | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const loadUsers = () => {
    setLoading(true)
    fetchAdminUsers({ client: adminClient })
      .then(({ data }) => {
        setRows((data.items ?? []) as UserRecord[])
        setMessage(data.message ?? null)
        setError(null)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '加载失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { loadUsers() }, [])

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
      loadUsers()
    } catch (err) {
      if (err instanceof Error && err.message) {
        setError(err.message)
      }
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
          loadUsers()
        } catch (err) {
          setError(err instanceof Error ? err.message : '删除失败')
        }
      },
    })
  }

  const roleLabel = (role: string) => {
    switch (role) {
      case 'admin': return '管理员'
      case 'viewer': return '只读'
      default: return '普通用户'
    }
  }

  const columns: ColumnsType<UserRecord> = [
    { title: '登录名', dataIndex: 'login', width: 140 },
    { title: '邮箱', dataIndex: 'email', width: 200 },
    {
      title: '角色',
      dataIndex: 'role',
      width: 100,
      render: (r: string) => (
        <Tag color={r === 'admin' ? 'blue' : r === 'viewer' ? 'default' : 'green'}>
          {roleLabel(r)}
        </Tag>
      ),
    },
    { title: '绑定仓库', dataIndex: 'boundRepos', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (s: string) => (
        <Tag color={s === 'active' ? 'green' : 'red'}>{s === 'active' ? '启用' : '禁用'}</Tag>
      ),
    },
    { title: '最近登录', dataIndex: 'lastLogin', width: 170, render: (v: string) => v || '-' },
    {
      title: '操作',
      width: 140,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(record)}>编辑</Button>
          <Button type="link" size="small" danger onClick={() => handleDelete(record)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>用户管理</h1>
        <p>管理平台用户账号，支持创建、编辑、启用/禁用及删除用户。</p>
      </div>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} closable onClose={() => setError(null)} />}
      {message && <Alert type="info" showIcon message={message} style={{ marginBottom: 16 }} />}
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
        locale={{ emptyText: '暂无用户数据' }}
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
