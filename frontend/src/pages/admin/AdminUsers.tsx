import { Button, Table, Tag, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import { communityUsers } from '../../../../frontend/src/mock/adminData'
import type { CommunityUser } from '../../../../frontend/src/mock/adminData'

export default function AdminUsers() {
  const [rows, setRows] = useState(communityUsers)

  const toggleStatus = (id: string) => {
    setRows((prev) =>
      prev.map((u) =>
        u.id === id
          ? { ...u, status: u.status === 'active' ? ('suspended' as const) : ('active' as const) }
          : u,
      ),
    )
    message.success('用户状态已更新（演示）')
  }

  const columns: ColumnsType<CommunityUser> = [
    { title: '用户 ID', dataIndex: 'id', width: 90 },
    { title: 'GitHub 账号', dataIndex: 'login' },
    { title: '邮箱', dataIndex: 'email' },
    { title: '绑定仓库', dataIndex: 'boundRepos', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: CommunityUser['status']) => (
        <Tag color={s === 'active' ? 'green' : 'red'}>
          {s === 'active' ? '正常' : '已封禁'}
        </Tag>
      ),
    },
    { title: '最近登录', dataIndex: 'lastLogin', width: 190 },
    { title: '注册时间', dataIndex: 'createdAt', width: 190 },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_, record) => (
        <Button size="small" type="link" onClick={() => toggleStatus(record.id)}>
          {record.status === 'active' ? '封禁' : '解封'}
        </Button>
      ),
    },
  ]

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <h1>用户管理</h1>
        <p>查看社区用户绑定情况与账号状态（对应普通社区用户管理）</p>
      </div>

      <Table
        className="admin-card"
        columns={columns}
        dataSource={rows}
        rowKey="id"
        pagination={{ pageSize: 8 }}
      />
    </div>
  )
}
