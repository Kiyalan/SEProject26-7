import { useState, useCallback } from 'react';
import { Card, Table, Typography, Space, Button, Tag, Select, Pagination } from 'antd';
import { SyncOutlined, ThunderboltOutlined, EyeOutlined } from '@ant-design/icons';
import PageShell from '../components/layout/PageShell';
// 保留你原有的所有 import，比如接口、上下文

const { Title, Text } = Typography;
const { Option } = Select;

export default function IssueList() {
  // 完全保留你原有的状态、接口、数据逻辑
  const [status, setStatus] = useState('all');
  const [type, setType] = useState('all');
  const [loading, setLoading] = useState(false);
  const [issues, setIssues] = useState<any[]>([]);

  // 保留原有分析、查看详情逻辑
  const handleAnalyze = useCallback((id: string) => {
    // 原有分析逻辑
  }, []);

  const handleViewDetail = useCallback((id: string) => {
    // 原有详情逻辑
  }, []);

  // 表格列定义，保留原有字段
  const columns = [
    {
      title: 'Issue',
      dataIndex: 'title',
      key: 'title',
      render: (text: string, record: any) => (
        <div>
          <div style={{ fontWeight: 600, color: '#111827', marginBottom: 4 }}>
            #{record.number} {text}
          </div>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {record.author} · {record.createdAt}
          </Text>
          <Tag color="success" style={{ marginTop: 6, borderRadius: 8, fontSize: 11 }}>
            {record.state}
          </Tag>
        </div>
      ),
    },
    {
      title: 'AI 分类',
      dataIndex: 'category',
      key: 'category',
      render: (val: string) => (
        <Text type="secondary" style={{ fontSize: 13 }}>
          {val || '未分析'}
        </Text>
      ),
    },
    {
      title: '置信度',
      dataIndex: 'confidence',
      key: 'confidence',
      render: (val: number | string) => (
        <Text type="secondary" style={{ fontSize: 13 }}>{val || '—'}</Text>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_: any, record: any) => (
        <Space size={8}>
          <Button size="small" icon={<ThunderboltOutlined />} onClick={() => handleAnalyze(record.id)}>
            分析
          </Button>
          <Button size="small" icon={<EyeOutlined />} onClick={() => handleViewDetail(record.id)}>
            详情
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageShell
      title="Issue 智能分析"
      description="从 GitHub 拉取 Issue 并生成类型判断与回复建议（配置 LLM 后使用 OpenRouter 增强）"
      actions={
        <Button type="primary" icon={<SyncOutlined />} loading={loading}>
          分析当前列表
        </Button>
      }
    >
      {/* 主卡片，包裹筛选+表格 */}
      <Card
        variant="outlined"
        style={{
          borderRadius: 12,
          boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
          border: '1px solid #e5e7eb',
        }}
        bodyStyle={{ padding: '24px 28px' }}
      >
        {/* 筛选栏，模块化顶部 */}
        <Space size={12} style={{ marginBottom: 20 }} wrap>
          <Select value={status} onChange={setStatus} style={{ width: 140 }}>
            <Option value="all">全部状态</Option>
            <Option value="open">开启</Option>
            <Option value="closed">关闭</Option>
          </Select>

          <Select value={type} onChange={setType} style={{ width: 180 }}>
            <Option value="all">全部类型（含未分析）</Option>
            <Option value="bug">Bug</Option>
            <Option value="feature">功能需求</Option>
            <Option value="question">咨询</Option>
          </Select>

          <Button icon={<SyncOutlined />} loading={loading}>
            刷新
          </Button>
        </Space>

        {/* 统计信息 */}
        <div style={{ marginBottom: 16 }}>
          <Text style={{ fontWeight: 600, fontSize: 14 }}>共 {issues.length} 条 Issue</Text>
        </div>

        {/* 表格 */}
        <Table
          columns={columns}
          dataSource={issues}
          rowKey="id"
          pagination={false}
          loading={loading}
          style={{ marginBottom: 20 }}
        />

        {/* 分页 */}
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Pagination defaultCurrent={1} total={issues.length} />
        </div>
      </Card>
    </PageShell>
  );
}