import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Card, Tag, Typography, Divider } from 'antd';
import { GithubOutlined } from '@ant-design/icons';
import { fetchKnowledge, type KnowledgeOverview } from '../../api/generated';
import { useRepoContext } from '../../context/RepoContext';
import QuickActions from '../QuickActions';

const { Text } = Typography;

// 语言颜色映射完全保留原有逻辑
const LANG_COLORS: Record<string, string> = {
  TypeScript: '#3178c6',
  JavaScript: '#f1e05a',
  Python: '#3572A5',
  CSS: '#563d7c',
  HTML: '#e34c26',
  JSON: '#292929',
  Markdown: '#083fa1',
};

export default function ContextPanel() {
  const location = useLocation();
  const { currentRepoId, currentRepo } = useRepoContext();
  const [knowledge, setKnowledge] = useState<KnowledgeOverview | null>(null);

  // 完全保留原有请求逻辑
  useEffect(() => {
    if (!currentRepoId) return;
    fetchKnowledge({ path: { repoId: currentRepoId } })
      .then(({ data }) => setKnowledge(data))
      .catch(() => setKnowledge(null));
  }, [currentRepoId, location.pathname]);

  const showActions = !location.pathname.startsWith('/settings');

  const langEntries = Object.entries(knowledge?.languages || {});
  const langTotal = langEntries.reduce((s, [, c]) => s + c, 0) || 1;

  return (
    <aside className="gh-aside" style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* 模块1：仓库概览（双列布局，缩短卡片高度） */}
      {currentRepo && (
        <Card
          variant="outlined"
          style={{
            borderRadius: 12,
            boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
            border: '1px solid #e5e7eb',
          }}
          bodyStyle={{ padding: '18px 20px' }}
          title={<span style={{ fontWeight: 700, fontSize: 15 }}>仓库概览</span>}
        >
          {/* 双列网格布局，大幅压缩高度 */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16 }}>
            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>语言</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {currentRepo.language}
              </div>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>Stars</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {currentRepo.stars.toLocaleString()}
              </div>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>Open Issues</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {currentRepo.openIssues}
              </div>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>默认分支</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {currentRepo.defaultBranch || 'main'}
              </div>
            </div>
          </div>

          {currentRepo.htmlUrl && (
            <>
              <Divider style={{ margin: '0 0 12px 0' }} />
              <a
                className="gh-link"
                href={currentRepo.htmlUrl}
                target="_blank"
                rel="noreferrer"
                style={{ fontSize: 13, display: 'flex', alignItems: 'center', gap: 6, color: '#165DFF' }}
              >
                <GithubOutlined />
                在 GitHub 上查看
              </a>
            </>
          )}
        </Card>
      )}

      {/* 模块2：知识库索引（同步双列优化，保留代码行数） */}
      {knowledge && knowledge.status === 'ready' && (
        <Card
          variant="outlined"
          style={{
            borderRadius: 12,
            boxShadow: '0 2px 12px rgba(0, 0, 0, 0.06)',
            border: '1px solid #e5e7eb',
          }}
          bodyStyle={{ padding: '18px 20px' }}
          title={<span style={{ fontWeight: 700, fontSize: 15 }}>知识库索引</span>}
        >
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>状态</Text>
              <Tag color="success" style={{ borderRadius: 10, padding: '0 10px', fontSize: 12 }}>
                已就绪
              </Tag>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>文件数</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {knowledge.fileCount}
              </div>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>代码行数</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {knowledge.lineCount ?? '—'}
              </div>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>检索片段</Text>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#111827' }}>
                {knowledge.chunkCount}
              </div>
            </div>

            {knowledge.indexedAt && (
              <div>
                <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 3 }}>索引时间</Text>
                <div style={{ fontSize: 13, color: '#6B7280' }}>{knowledge.indexedAt}</div>
              </div>
            )}
          </div>

          {langEntries.length > 0 && (
            <>
              <Divider style={{ margin: '12px 0' }} />
              <div>
                <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 10 }}>语言分布</Text>
                <div className="gh-lang-bar" style={{ marginBottom: 10 }}>
                  {langEntries.map(([lang, count]) => (
                    <div
                      key={lang}
                      className="gh-lang-segment"
                      title={`${lang}: ${count}`}
                      style={{
                        width: `${(count / langTotal) * 100}%`,
                        background: LANG_COLORS[lang] || '#8b949e',
                      }}
                    />
                  ))}
                </div>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {langEntries.map(([l, c]) => `${l} ${Math.round((c / langTotal) * 100)}%`).join(' · ')}
                </Text>
              </div>
            </>
          )}
        </Card>
      )}

      {/* 模块3：快捷操作 + 自然语言命令 */}
      {showActions && <QuickActions compact />}
    </aside>
  );
}
