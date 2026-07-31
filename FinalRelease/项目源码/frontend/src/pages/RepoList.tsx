import { Alert, Spin, Card, Tag, Typography, Space, Button } from 'antd';
import { SyncOutlined, StarOutlined, ExclamationCircleOutlined, GithubOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import PageShell from '../components/layout/PageShell';
import { useRepoContext } from '../context/RepoContext';
import { getUsername } from '../lib/AuthAxios';

const { Text, Title, Paragraph } = Typography;

const statusLabel: Record<string, { text: string; color: string }> = {
  synced: { text: '已同步', color: 'success' },
  syncing: { text: '同步中', color: 'processing' },
  error: { text: '失败', color: 'error' },
};

export default function RepoList() {
  const navigate = useNavigate();
  const { repoList, isRepoListPending, isRepoListFetching, syncRepoList } = useRepoContext();
  const [error, setError] = useState<string | null>(null);
  const loading = isRepoListPending || isRepoListFetching;

  const loadRepos = useCallback(async () => {
    setError(null);
    try {
      await syncRepoList();
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载仓库失败');
    }
  }, [syncRepoList]);

  const getStatusBorderColor = (status: string): string => {
    switch (status) {
      case 'synced':
        return '#00B42A';
      case 'syncing':
        return '#165DFF';
      case 'error':
        return '#F53F3F';
      default:
        return '#9CA3AF';
    }
  };

  return (
    <PageShell
      title="你的仓库"
      description={`已连接 GitHub 账号 ${getUsername()} · 共 ${repoList.length} 个仓库`}
      actions={
        <Button type="primary" size="middle" icon={<SyncOutlined />} onClick={loadRepos} disabled={loading}>
          刷新仓库
        </Button>
      }
    >
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 24 }} />}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin size="large" />
          <div style={{ marginTop: 16, color: '#6B7280' }}>加载仓库列表中...</div>
        </div>
      ) : repoList.length === 0 ? (
        <Card variant="outlined" style={{ borderRadius: 12, textAlign: 'center', padding: '48px 0' }}>
          <Text type="secondary">暂无仓库，请确认 GitHub OAuth 权限包含 repo 读取。</Text>
        </Card>
      ) : (
        /* 仓库列表，占满主内容区 */
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20, width: '100%' }}>
          {repoList.map((repo: any) => (
            <Card
              key={repo.id}
              hoverable
              variant="outlined"
              className="repo-card repo-card-main"
              style={{
                borderRadius: 16,
                borderLeft: `5px solid ${getStatusBorderColor(repo.syncStatus)}`,
                boxShadow: '0 4px 16px rgba(0, 0, 0, 0.08)',
              }}
              bodyStyle={{ padding: '28px 32px' }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Space size="middle" align="center" style={{ marginBottom: 14 }}>
                    <Title
                      level={4}
                      className="repo-card-title"
                      style={{ margin: 0, cursor: 'pointer' }}
                      onClick={() => navigate(`/repos/${repo.id}`)}
                    >
                      {repo.fullName}
                    </Title>

                    <Tag
                      color={statusLabel[repo.syncStatus]?.color || 'default'}
                      style={{ borderRadius: 12, padding: '0 12px', fontSize: 12, height: 24, lineHeight: '22px' }}
                    >
                      {statusLabel[repo.syncStatus]?.text || '未知'}
                    </Tag>

                    {repo.private && (
                      <Tag color="default" style={{ borderRadius: 12, padding: '0 10px', fontSize: 12, height: 24, lineHeight: '22px' }}>
                        私有
                      </Tag>
                    )}
                  </Space>

                  <Paragraph
                    type="secondary"
                    style={{ marginBottom: 18, minHeight: 24, fontSize: 14 }}
                    ellipsis={{ rows: 2 }}
                  >
                    {repo.description || '暂无描述'}
                  </Paragraph>

                  <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
                    {repo.language && repo.language !== '--' && (
                      <Space size={8}>
                        <span
                          style={{
                            width: 12,
                            height: 12,
                            borderRadius: '50%',
                            background: '#3178c6',
                            display: 'inline-block',
                          }}
                        />
                        <Text type="secondary" style={{ fontSize: 14 }}>{repo.language}</Text>
                      </Space>
                    )}

                    <Space size={8}>
                      <StarOutlined style={{ color: '#9CA3AF', fontSize: 16 }} />
                      <Text type="secondary" style={{ fontSize: 14 }}>{repo.stars.toLocaleString()}</Text>
                    </Space>

                    <Space size={8}>
                      <ExclamationCircleOutlined style={{ color: '#9CA3AF', fontSize: 16 }} />
                      <Text type="secondary" style={{ fontSize: 14 }}>{repo.openIssues}</Text>
                    </Space>

                    {repo.lastSync && (
                      <Text type="secondary" style={{ fontSize: 14 }}>更新：{repo.lastSync}</Text>
                    )}
                  </div>
                </div>

                <Space size={10}>
                  <Button
                    type="primary"
                    size="large"
                    icon={<ArrowRightOutlined />}
                    onClick={() => navigate(`/repos/${repo.id}`)}
                    style={{ height: 40, paddingLeft: 20, paddingRight: 20, borderRadius: 10 }}
                  >
                    进入仓库
                  </Button>

                  {repo.htmlUrl && (
                    <Button
                      size="large"
                      icon={<GithubOutlined />}
                      href={repo.htmlUrl}
                      target="_blank"
                      rel="noreferrer"
                      style={{ height: 40, borderRadius: 10 }}
                    >
                      GitHub
                    </Button>
                  )}
                </Space>
              </div>
            </Card>
          ))}
        </div>
      )}
    </PageShell>
  );
}