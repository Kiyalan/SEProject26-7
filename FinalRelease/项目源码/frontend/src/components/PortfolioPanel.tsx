import { useEffect, useState } from 'react';
import { Spin, Card, Tag, Typography, Space, Divider } from 'antd';
import { fetchPortfolioOverview, type PortfolioOverview } from '../api/generated';

const { Text, Title } = Typography;

export default function PortfolioPanel() {
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<PortfolioOverview | null>(null);
  const [loading, setLoading] = useState(false);

  // 完全保留原有请求逻辑
  useEffect(() => {
    if (!open || data) return;
    setLoading(true);
    fetchPortfolioOverview()
      .then(({ data }) => setData(data))
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [open, data]);

  return (
    <Card
      variant="outlined"
      style={{
        borderRadius: 12,
        boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
        border: '1px solid #e5e7eb',
        marginBottom: 16,
      }}
      bodyStyle={{ padding: '20px 22px' }}
      title={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%' }}>
          <Title level={5} style={{ margin: 0, fontSize: 15, fontWeight: 700 }}>
            多仓库总览（样本）
          </Title>
          <button
            type="button"
            className="gh-btn gh-btn-sm"
            onClick={() => setOpen((v) => !v)}
            style={{ fontSize: 12 }}
          >
            {open ? '收起' : '展开'}
          </button>
        </div>
      }
    >
      {open && (
        <div>
          {/* 加载状态 */}
          {loading && (
            <div style={{ textAlign: 'center', padding: 24 }}>
              <Spin />
            </div>
          )}

          {/* 数据内容 */}
          {!loading && data && (
            <>
              {/* 模块1：核心统计指标 */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 20 }}>
                <div style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 18, fontWeight: 700, color: '#111827', marginBottom: 4 }}>
                    {data.summary.repoCount}
                  </div>
                  <Text type="secondary" style={{ fontSize: 12 }}>仓库数</Text>
                </div>
                <div style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 18, fontWeight: 700, color: '#00B42A', marginBottom: 4 }}>
                    {data.summary.indexedCount}
                  </div>
                  <Text type="secondary" style={{ fontSize: 12 }}>已建知识库</Text>
                </div>
                <div style={{ textAlign: 'center' }}>
                  <div style={{ fontSize: 18, fontWeight: 700, color: '#165DFF', marginBottom: 4 }}>
                    {data.summary.indexRate}%
                  </div>
                  <Text type="secondary" style={{ fontSize: 12 }}>索引覆盖率</Text>
                </div>
              </div>

              <Divider style={{ margin: '16px 0' }} />

              {/* 模块2：语言分布 */}
              <div style={{ marginBottom: 20 }}>
                <Text style={{ fontSize: 14, fontWeight: 600, display: 'block', marginBottom: 12 }}>
                  语言分布（GitHub 主语言）
                </Text>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {data.languageBreakdown.map((row) => (
                    <div
                      key={row.language}
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        fontSize: 13,
                      }}
                    >
                      <Space size={8}>
                        <span
                          style={{
                            width: 10,
                            height: 10,
                            borderRadius: '50%',
                            background: '#3178c6',
                            display: 'inline-block',
                          }}
                        />
                        <span>{row.language}</span>
                      </Space>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {row.count} 个 · {row.percent}%
                      </Text>
                    </div>
                  ))}
                </div>
              </div>

              <Divider style={{ margin: '16px 0' }} />

              {/* 模块3：技术聚类 */}
              <div style={{ marginBottom: 20 }}>
                <Text style={{ fontSize: 14, fontWeight: 600, display: 'block', marginBottom: 12 }}>
                  技术聚类（规则）
                </Text>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {Object.entries(data.clusters).map(([name, repos]) => (
                    <div key={name}>
                      <strong style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>{name}</strong>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {(repos as string[]).join(' · ') || '--'}
                      </Text>
                    </div>
                  ))}
                </div>
              </div>

              <Divider style={{ margin: '16px 0' }} />

              {/* 模块4：仓库列表 */}
              <div>
                <Text style={{ fontSize: 14, fontWeight: 600, display: 'block', marginBottom: 12 }}>
                  仓库列表（按最近 push）
                </Text>
                <div style={{ maxHeight: 220, overflow: 'auto' }}>
                  {data.repos.slice(0, 12).map((repo) => (
                    <div
                      key={repo.repoId}
                      style={{
                        padding: '8px 0',
                        borderBottom: '1px solid #f0f0f0',
                        fontSize: 13,
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                      }}
                    >
                      <span>{repo.fullName}</span>
                      <Space size={8}>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {repo.language} · ★{repo.stars}
                        </Text>
                        {repo.knowledge.indexed ? (
                          <Tag color="success" style={{ fontSize: 11, padding: '0 6px', borderRadius: 8 }}>
                            已索引
                          </Tag>
                        ) : (
                          <Tag color="default" style={{ fontSize: 11, padding: '0 6px', borderRadius: 8 }}>
                            未索引
                          </Tag>
                        )}
                      </Space>
                    </div>
                  ))}
                </div>
              </div>

              {/* 备注 */}
              {data.notes.length > 0 && (
                <>
                  <Divider style={{ margin: '16px 0' }} />
                  <ul style={{ margin: '12px 0 0 0', paddingLeft: 18, color: '#6B7280', fontSize: 12 }}>
                    {data.notes.map((n) => (
                      <li key={n}>{n}</li>
                    ))}
                  </ul>
                </>
              )}
            </>
          )}

          {/* 加载失败提示 */}
          {!loading && !data && (
            <Text type="secondary" style={{ fontSize: 12, display: 'block', textAlign: 'center' }}>
              加载失败，请确认已登录 GitHub
            </Text>
          )}
        </div>
      )}
    </Card>
  );
}