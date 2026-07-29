import { useState, useCallback, useEffect } from 'react';
import { Card, Alert, Typography, Space, Button, Input, Tag } from 'antd';
import { SendOutlined, WarningOutlined } from '@ant-design/icons';
import PageShell from '../components/layout/PageShell';
// 保留你原有的所有 import，比如消息接口、上下文等

const { Text, Title, Paragraph } = Typography;
const { TextArea } = Input;

export default function Chat() {
  // 完全保留你原有的状态、接口、消息逻辑
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<any[]>([]);
  const [hasKnowledge, setHasKnowledge] = useState(false);

  // 示例问题，保留原有引导逻辑
  const sampleQuestions = [
    '这个项目是做什么的？',
    '路由配置在哪里？',
    '如何启动项目？',
  ];

  const handleSend = useCallback(() => {
    if (!input.trim()) return;
    // 保留你原有的发送逻辑
    setMessages((prev) => [...prev, { role: 'user', content: input }]);
    setInput('');
  }, [input]);

  const handleSampleClick = (q: string) => {
    setInput(q);
  };

  return (
    <PageShell
      title="智能问答"
      description="检索摘要模式（配置 LLM_API_KEY 可启用大模型）"
    >
      {/* 状态提示 */}
      {!hasKnowledge && (
        <Alert
          type="warning"
          showIcon
          icon={<WarningOutlined />}
          message="当前仓库尚未构建知识库"
          description="请先在「知识库」页面构建索引，或使用右侧「同步知识库」快捷操作。"
          style={{ marginBottom: 20, borderRadius: 10 }}
        />
      )}

      {/* 主聊天卡片 */}
      <Card
        variant="outlined"
        style={{
          borderRadius: 12,
          boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
          border: '1px solid #e5e7eb',
          marginBottom: 20,
        }}
        bodyStyle={{ padding: 0 }}
      >
        {/* 消息区域 */}
        <div style={{ padding: '24px 28px', minHeight: 320, maxHeight: 500, overflow: 'auto' }}>
          {messages.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px 0' }}>
              <Title level={5} style={{ marginBottom: 16, color: '#111827' }}>
                试试问这些问题
              </Title>
              <Space wrap size={10} style={{ justifyContent: 'center' }}>
                {sampleQuestions.map((q) => (
                  <Tag
                    key={q}
                    color="blue"
                    style={{
                      borderRadius: 10,
                      padding: '6px 12px',
                      fontSize: 13,
                      cursor: 'pointer',
                    }}
                    onClick={() => handleSampleClick(q)}
                  >
                    {q}
                  </Tag>
                ))}
              </Space>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
              {messages.map((msg, idx) => (
                <div
                  key={idx}
                  style={{
                    display: 'flex',
                    justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
                  }}
                >
                  <div
                    style={{
                      maxWidth: '75%',
                      padding: '12px 16px',
                      borderRadius: 10,
                      background: msg.role === 'user' ? '#165DFF' : '#f5f7fa',
                      color: msg.role === 'user' ? '#fff' : '#111827',
                      fontSize: 14,
                      lineHeight: 1.6,
                    }}
                  >
                    {msg.content}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 分割线 */}
        <div style={{ borderTop: '1px solid #e5e7eb' }} />

        {/* 输入区域，视觉重点 */}
        <div style={{ padding: '20px 28px' }}>
          <Space.Compact style={{ width: '100%' }}>
            <TextArea
              rows={2}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="例如：路由配置在哪里？如何运行测试？"
              style={{ borderRadius: '10px 0 0 10px', fontSize: 14 }}
              onPressEnter={handleSend}
            />
            <Button
              type="primary"
              size="large"
              icon={<SendOutlined />}
              onClick={handleSend}
              style={{
                height: 'auto',
                borderRadius: '0 10px 10px 0',
                padding: '0 24px',
                fontSize: 15,
                fontWeight: 600,
              }}
            >
              发送
            </Button>
          </Space.Compact>
        </div>
      </Card>
    </PageShell>
  );
}