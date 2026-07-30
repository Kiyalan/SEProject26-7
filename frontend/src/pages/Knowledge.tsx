import { FileIcon, FileDirectoryIcon, GitCommitIcon, PackageIcon, SyncIcon } from '@primer/octicons-react'
import { Alert, Modal, Spin, message, Card, Typography, Space, Button, Tag, Row, Col } from 'antd'
import {
  ReloadOutlined,
  BookOutlined,
  FileTextOutlined,
  DatabaseOutlined,
  InfoCircleOutlined
} from '@ant-design/icons'
import type { DataNode } from 'antd/es/tree'
import { Tree } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import PageShell from '../components/layout/PageShell'
import PortfolioPanel from '../components/PortfolioPanel'
import { useRepoContext } from '../context/RepoContext'
import {
  buildKnowledge,
 // resetKnowledge,
  compareKnowledgeCommits,
  exportRepoFaq,
  fetchKnowledge,
  fetchKnowledgeBuildErrors,
  fetchKnowledgeBuildTasks,
  fetchKnowledgeGraphStatus,
  fetchKnowledgePolicy,
  fetchKnowledgeWiki,
  fetchRepoFaq,
  generateKnowledgeWiki,
  generateRepoFaq,
  type CommitCompareResult,
  type FaqItem,
  type FaqListResponse,
  type IndexedCommit,
  type KnowledgeBuildError,
  type KnowledgeBuildTask,
  type KnowledgeGraphStatus,
  type KnowledgeOverview,
  type KnowledgePolicy,
  type KnowledgeWiki,
} from '../api/generated'
import type { KnowledgeNode } from '../lib/FrontendTypes'
import { fetchRepoProgress, sleep } from '../lib/progress'

const { Title, Text } = Typography

const faqCategoryLabels: Record<string, string> = {
  overview: '概览',
  'getting-started': '入门',
  api: '接口',
  deployment: '部署',
  architecture: '架构',
  troubleshooting: '排查',
}

interface BuildPhase {
  key: string
  label: string
}

const BUILD_PHASES: BuildPhase[] = [
  { key: 'preparing', label: '准备' },
  { key: 'git_sync', label: '同步仓库' },
  { key: 'register', label: '注册' },
  { key: 'analyze', label: '源码分析' },
  { key: 'graphrag', label: '构建图谱' },
  { key: 'update', label: '增量更新' },
  { key: 'indexing', label: '索引元数据' },
  { key: 'quality', label: '质量评分' },
]

function getPhaseStatus(phaseKey: string, currentStage: string, buildProgress: number): 'done' | 'active' | 'pending' {
  const currentIdx = BUILD_PHASES.findIndex((p) => p.key === currentStage)
  const phaseIdx = BUILD_PHASES.findIndex((p) => p.key === phaseKey)
  if (phaseIdx < currentIdx || (buildProgress >= 100 && phaseIdx < currentIdx)) return 'done'
  if (phaseIdx === currentIdx && buildProgress > 0) return 'active'
  if (currentIdx < 0 && buildProgress >= 100) return 'done'
  return 'pending'
}

function toTreeData(nodes: KnowledgeNode[]): DataNode[] {
  return nodes.map((node) => ({
    key: node.key,
    title: node.title,
    icon:
      node.type === 'folder' ? (
        <FileDirectoryIcon size={14} />
      ) : node.type === 'module' ? (
        <PackageIcon size={14} />
      ) : (
        <FileIcon size={14} />
      ),
    children: node.children ? toTreeData(node.children) : undefined,
  }))
}

export default function Knowledge() {
  const { currentRepoId, setCurrentRepo, repoList } = useRepoContext()
  const [loading, setLoading] = useState(false)
  const [building, setBuilding] = useState(false)
  const [buildMessage, setBuildMessage] = useState('')
  const [buildProgress, setBuildProgress] = useState(0)
  const [buildStage, setBuildStage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [overview, setOverview] = useState<KnowledgeOverview | null>(null)
  const [selectedCommit, setSelectedCommit] = useState('')
  const [compareBase, setCompareBase] = useState('')
  const [compareHead, setCompareHead] = useState('')
  const [compareResult, setCompareResult] = useState<CommitCompareResult | null>(null)
  const [comparing, setComparing] = useState(false)
  const [policy, setPolicy] = useState<KnowledgePolicy | null>(null)
  const [graphStatus, setGraphStatus] = useState<KnowledgeGraphStatus | null>(null)
  const [wiki, setWiki] = useState<KnowledgeWiki | null>(null)
  const [selectedWikiPageId, setSelectedWikiPageId] = useState('')
  const [generatingWiki, setGeneratingWiki] = useState(false)
  const [buildTasks, setBuildTasks] = useState<KnowledgeBuildTask[]>([])
  const [selectedTaskId, setSelectedTaskId] = useState('')
  const [taskErrors, setTaskErrors] = useState<KnowledgeBuildError[]>([])
  const [faq, setFaq] = useState<FaqListResponse | null>(null)
  const [generatingFaq, setGeneratingFaq] = useState(false)
  const [selectedFaqId, setSelectedFaqId] = useState('')

  const loadTasks = useCallback(async (id: string) => {
    try {
      const { data } = await fetchKnowledgeBuildTasks({
        path: { repoId: id },
        query: { limit: 10 },
      })
      setBuildTasks(data.items)
      const first = data.items[0]
      if (first) {
        setSelectedTaskId((prev) => prev || first.taskId)
      }
    } catch {
      setBuildTasks([])
    }
  }, [])

  const loadFaq = useCallback(async (id: string) => {
    try {
      const { data } = await fetchRepoFaq({ path: { repoId: id } })
      setFaq(data)
      setSelectedFaqId(data.items[0]?.id ?? '')
    } catch {
      setFaq(null)
      setSelectedFaqId('')
    }
  }, [])

  const loadOverview = useCallback(
    async (id: string, commitSha?: string) => {
      if (!id) return
      setLoading(true)
      setError(null)
      try {
        const { data } = await fetchKnowledge({
          path: { repoId: id },
          query: commitSha ? { commit: commitSha } : undefined,
        })
        setOverview(data)
        const active = commitSha || data.commitSha || data.settings?.activeCommitSha || ''
        setSelectedCommit(active)
        const commits = data.commits || []
        if (commits.length >= 2) {
          setCompareBase(commits[1].commitSha)
          setCompareHead(commits[0].commitSha)
        }
      } catch (err) {
        const raw = err instanceof Error ? err.message : '加载失败'
        const friendly = /Unexpected end of file|EOF|ECONNRESET|Failed to fetch/i.test(raw)
          ? '知识库概览响应中断（常见于文件树过大或 CodeWiki 暂不可用）。已尽量展示其它面板，可刷新或重新构建。'
          : raw
        setError(friendly)
      } finally {
        setLoading(false)
      }
    },
    [],
  )

  // 生成Wiki包装函数
  const handleGenerateWiki = useCallback(async () => {
    if (!currentRepoId) return
    setGeneratingWiki(true)
    try {
      await generateKnowledgeWiki({ path: { repoId: currentRepoId } })
      message.success('Wiki 生成任务已提交')
      const { data } = await fetchKnowledgeWiki({ path: { repoId: currentRepoId }, query: { language: 'zh' } })
      setWiki(data)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '生成失败')
    } finally {
      setGeneratingWiki(false)
    }
  }, [currentRepoId])

  // 生成FAQ包装函数
  const handleGenerateFaq = useCallback(async () => {
    if (!currentRepoId) return
    setGeneratingFaq(true)
    try {
      await generateRepoFaq({ path: { repoId: currentRepoId } })
      message.success('FAQ 生成任务已提交')
      loadFaq(currentRepoId)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '生成失败')
    } finally {
      setGeneratingFaq(false)
    }
  }, [currentRepoId, loadFaq])

  useEffect(() => {
    if (currentRepoId) {
      loadOverview(currentRepoId)
      loadTasks(currentRepoId)
      loadFaq(currentRepoId)
      fetchKnowledgePolicy({ path: { repoId: currentRepoId } })
        .then(({ data }) => setPolicy(data))
        .catch(() => setPolicy(null))
      fetchKnowledgeGraphStatus({ path: { repoId: currentRepoId } })
        .then(({ data: status }) => setGraphStatus(status))
        .catch(() => setGraphStatus(null))
      fetchKnowledgeWiki({ path: { repoId: currentRepoId }, query: { language: 'zh' } })
        .then(({ data }) => {
          setWiki(data)
          setSelectedWikiPageId(data.pages[0]?.id ?? '')
        })
        .catch(() => {
          setWiki(null)
          setSelectedWikiPageId('')
        })
    }
  }, [currentRepoId, loadOverview, loadTasks, loadFaq])

  useEffect(() => {
    if (!currentRepoId || !selectedTaskId) {
      setTaskErrors([])
      return
    }
    fetchKnowledgeBuildErrors({
      path: { repoId: currentRepoId, taskId: selectedTaskId },
    })
      .then(({ data }) => setTaskErrors(data.items))
      .catch(() => setTaskErrors([]))
  }, [currentRepoId, selectedTaskId])

  const handleSelectCommit = (commit: IndexedCommit) => {
    setSelectedCommit(commit.commitSha)
  }

  const handleBuild = async () => {
    if (!currentRepoId) return
    setBuilding(true)
    setBuildMessage('正在启动构建…')
    setBuildProgress(0)
    setBuildStage('')
    setError(null)

    let pollTimer: ReturnType<typeof setInterval> | null = null
    const stopPolling = () => {
      if (pollTimer) {
        clearInterval(pollTimer)
        pollTimer = null
      }
    }

    pollTimer = setInterval(async () => {
      try {
        const snapshot = await fetchRepoProgress(currentRepoId)
        const knowledge = snapshot.knowledge
        setBuildMessage(knowledge.message || knowledge.status)
        setBuildProgress(knowledge.progress ?? 0)
        setBuildStage(knowledge.stage ?? '')
        if (knowledge.status === 'done' || knowledge.status === 'error') {
          stopPolling()
          if (knowledge.status === 'error') {
            setError(knowledge.message || '构建失败')
          }
          setBuilding(false)
        }
      } catch {
        // 轮询失败时不打断构建
      }
    }, 1500)

    try {
      const { data } = await buildKnowledge({
        path: { repoId: currentRepoId },
      })

      const startedAsync = Boolean((data as { async?: boolean })?.async)
      if (startedAsync) {
        while (true) {
          const snapshot = await fetchRepoProgress(currentRepoId)
          const knowledge = snapshot.knowledge
          setBuildMessage(knowledge.message || '构建中…')
          setBuildProgress(knowledge.progress ?? 0)
          setBuildStage(knowledge.stage ?? '')
          if (knowledge.status === 'done') {
            break
          }
          if (knowledge.status === 'error') {
            throw new Error(knowledge.message || '构建失败')
          }
          if (knowledge.status === 'idle') {
            await sleep(1500)
            continue
          }
          await sleep(1500)
        }
      }

      await loadOverview(currentRepoId)
      await loadTasks(currentRepoId)
      await loadFaq(currentRepoId)
      fetchKnowledgeGraphStatus({ path: { repoId: currentRepoId } })
        .then(({ data: status }) => setGraphStatus(status))
        .catch(() => setGraphStatus(null))
      fetchKnowledgeWiki({ path: { repoId: currentRepoId }, query: { language: 'zh' } })
        .then(({ data: current }) => setWiki(current))
        .catch(() => setWiki(null))
    } catch (err) {
      setError(err instanceof Error ? err.message : '构建失败')
    } finally {
      stopPolling()
      setBuilding(false)
    }
  }

  const handleReset = async () => {
    if (!currentRepoId) return
    Modal.confirm({
      title: '确认重置知识库？',
      content:
        '将删除本地索引、FAQ、构建记录，并尝试删除 CodeWiki 中的图谱/wiki。之后需要重新「构建知识库」。',
      okText: '确认重置',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        setError(null)
        try {
    // 接口暂未同步，暂时屏蔽调用
    message.info('知识库重置功能暂未开放')
    // const { data } = await resetKnowledge({ path: { repoId: cur...
    // const payload = data as {
    //   message?: string
    //   codewikiDeleted?: boolean
    //   codewikiWarning?: string
    // }
    // if (payload.codewikiWarning) {
    //   message.warning(payload.message || '本地已重置，CodeWiki...
    // } else {
    //   message.success(payload.message || '知识库已重置')
    // }
    // setOverview(null)
    // setGraphStatus(null)
        } catch (err) {
          setError(err instanceof Error ? err.message : '重置失败')
        }
      },
    })
  }

  const handleCompare = async () => {
    if (!currentRepoId || !compareBase || !compareHead) return
    setComparing(true)
    try {
      const { data } = await compareKnowledgeCommits({
        path: { repoId: currentRepoId },
        query: { base: compareBase, head: compareHead },
      })
      setCompareResult(data)
    } catch (err) {
      message.error(err instanceof Error ? err.message : '对比失败')
    } finally {
      setComparing(false)
    }
  }

  return (
    <PageShell
      title="知识库"
      description="CodeWiki GraphRAG 代码知识图谱、按需 Wiki 与历史版本对比"
      actions={
        <Space size={10}>
          <Button type="primary" onClick={handleBuild} loading={building}>
            构建知识库
          </Button>
          <Button onClick={handleReset} disabled={building}>
            重置知识库
          </Button>
        </Space>
      }
    >
      {/* 全局错误提示 */}
      {error && (
        <Alert
          type="error"
          message={error}
          style={{ marginBottom: 0, borderRadius: 10 }}
          showIcon
        />
      )}

      {/* 最外层容器：模块化分区，加大间距 */}
      <div className="knowledge-page" style={{ display: 'flex', flexDirection: 'column', gap: 32 }}>
        {/* 多仓库总览（完全保留原有组件） */}
        <PortfolioPanel />

        {/* ===== 模块1：索引概览【核心状态区】 ===== */}
        <Card
          style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
          bodyStyle={{ padding: '26px 28px' }}
          title={<span style={{ fontWeight: 700, fontSize: 16 }}>CodeWiki GraphRAG 索引概览</span>}
        >
          <Row gutter={[28, 20]}>
            {/* 左侧：图谱核心指标 */}
            <Col span={12}>
              <Space size={48}>
                <div style={{ textAlign: 'center' }}>
                  <Title level={3} style={{ margin: 0, fontSize: 28, fontWeight: 700 }}>
                    {graphStatus?.nodeCount ?? 0}
                  </Title>
                  <Text type="secondary" style={{ fontSize: 13 }}>节点</Text>
                </div>
                <div style={{ textAlign: 'center' }}>
                  <Title level={3} style={{ margin: 0, fontSize: 28, fontWeight: 700 }}>
                    {graphStatus?.edgeCount ?? 0}
                  </Title>
                  <Text type="secondary" style={{ fontSize: 13 }}>边</Text>
                </div>
                <div style={{ textAlign: 'center' }}>
                  <Title level={3} style={{ margin: 0, fontSize: 28, fontWeight: 700 }}>
                    {graphStatus?.communityCount ?? 0}
                  </Title>
                  <Text type="secondary" style={{ fontSize: 13 }}>社区</Text>
                </div>
                <div style={{ textAlign: 'center' }}>
                  <Title level={3} style={{ margin: 0, fontSize: 28, fontWeight: 700 }}>
                    {graphStatus?.chunkCount ?? 0}
                  </Title>
                  <Text type="secondary" style={{ fontSize: 13 }}>片段</Text>
                </div>
              </Space>

              <div style={{ marginTop: 20 }}>
                <Tag style={{ fontSize: 12, padding: '0 10px', height: 24, lineHeight: '22px' }}>
                  状态：{graphStatus?.status || 'idle'}
                </Tag>
                {wiki?.status !== 'ready' && (
                  <Tag color="error" style={{ fontSize: 12, padding: '0 10px', height: 24, lineHeight: '22px' }}>
                    Wiki {wiki?.status || 'failed'}
                  </Tag>
                )}
              </div>
            </Col>

            {/* 右侧：质量 + 存储 */}
            <Col span={12}>
              <Row gutter={18}>
                <Col span={12}>
                  <div style={{ background: '#f7f8fa', padding: '18px 20px', borderRadius: 10, height: '100%' }}>
                    <Text strong style={{ fontSize: 14 }}>索引质量</Text>
                    <div style={{ marginTop: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <Text type="secondary" style={{ fontSize: 13 }}>状态</Text>
                        <Tag 
                          color={overview?.status === 'ready' ? 'success' : 'red'} 
                          style={{ fontSize: 12, margin: 0 }}
                        >
                          {overview?.status || 'failed'}
                        </Tag>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <Text type="secondary" style={{ fontSize: 13 }}>文件数</Text>
                        <Text style={{ fontSize: 14, fontWeight: 600 }}>{overview?.fileCount ?? 0}</Text>
                      </div>
                    </div>
                  </div>
                </Col>

                <Col span={12}>
                  <div style={{ background: '#f7f8fa', padding: '18px 20px', borderRadius: 10, height: '100%' }}>
                    <Text strong style={{ fontSize: 14 }}>存储与去重</Text>
                    <div style={{ marginTop: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <Text type="secondary" style={{ fontSize: 13 }}>索引提交</Text>
                        <Text style={{ fontSize: 14, fontWeight: 600 }}>{overview?.commits?.length ?? 0}</Text>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <Text type="secondary" style={{ fontSize: 13 }}>文件引用</Text>
                        <Text style={{ fontSize: 14, fontWeight: 600 }}>{overview?.fileCount ?? 0}</Text>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <Text type="secondary" style={{ fontSize: 13 }}>片段总数</Text>
                        <Text style={{ fontSize: 14, fontWeight: 600 }}>{graphStatus?.chunkCount ?? 0}</Text>
                      </div>
                    </div>
                  </div>
                </Col>
              </Row>
            </Col>
          </Row>
        </Card>

        {/* ===== 模块2：文档生成【高频功能区】 ===== */}
        <Row gutter={24}>
          <Col span={12}>
            <Card
              style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)', height: '100%' }}
              bodyStyle={{ padding: '24px 22px' }}
              title={
                <span style={{ fontWeight: 700, fontSize: 15 }}>
                  <BookOutlined style={{ marginRight: 8 }} />
                  项目 Wiki
                </span>
              }
              extra={
                <Button
                  type="primary"
                  onClick={handleGenerateWiki}
                  loading={generatingWiki}
                  disabled={!overview || overview.status !== 'ready'}
                >
                  生成 Wiki
                </Button>
              }
            >
              {wiki?.pages?.length ? (
                <Text type="secondary" style={{ fontSize: 13, lineHeight: 1.6 }}>
                  已生成 {wiki.pages.length} 个 Wiki 页面
                </Text>
              ) : (
                <Text type="secondary" style={{ fontSize: 13, lineHeight: 1.6 }}>
                  尚未生成 Wiki。按需生成后可在此浏览页面列表和内容。
                </Text>
              )}
            </Card>
          </Col>

          <Col span={12}>
            <Card
              style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)', height: '100%' }}
              bodyStyle={{ padding: '24px 22px' }}
              title={
                <span style={{ fontWeight: 700, fontSize: 15 }}>
                  <FileTextOutlined style={{ marginRight: 8 }} />
                  FAQ 聚类
                </span>
              }
              extra={
                <Space size={8}>
                  {/* 修复：md 改为 markdown，匹配接口枚举 */}
                  <Button 
                    onClick={() => exportRepoFaq({ path: { repoId: currentRepoId }, query: { format: 'markdown' } })}
                  >
                    导出 MD
                  </Button>
                  <Button 
                    onClick={() => exportRepoFaq({ path: { repoId: currentRepoId }, query: { format: 'json' } })}
                  >
                    导出 JSON
                  </Button>
                  <Button
                    type="primary"
                    onClick={handleGenerateFaq}
                    loading={generatingFaq}
                    disabled={!overview || overview.status !== 'ready'}
                  >
                    生成 FAQ
                  </Button>
                </Space>
              }
            >
              {faq?.items?.length ? (
                <Text type="secondary" style={{ fontSize: 13, lineHeight: 1.6 }}>
                  已生成 {faq.items.length} 条 FAQ
                </Text>
              ) : (
                <Text type="secondary" style={{ fontSize: 13, lineHeight: 1.6 }}>
                  尚未生成 FAQ，请先构建知识库后点击生成。
                </Text>
              )}
            </Card>
          </Col>
        </Row>

        {/* ===== 模块3：构建日志【任务排查区】 ===== */}
        <Card
          style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
          bodyStyle={{ padding: '24px 22px' }}
          title={
            <span style={{ fontWeight: 700, fontSize: 15 }}>
              <DatabaseOutlined style={{ marginRight: 8 }} />
              构建日志
            </span>
          }
          extra={<Button icon={<ReloadOutlined />} onClick={() => loadTasks(currentRepoId)}>刷新</Button>}
        >
          <Row gutter={24}>
            {/* 左侧日志列表 */}
            <Col span={6}>
              {buildTasks.map((task) => (
                <div
                  key={task.taskId}
                  onClick={() => setSelectedTaskId(task.taskId)}
                  style={{
                    padding: '12px 14px',
                    border: selectedTaskId === task.taskId ? '1px solid #165DFF' : '1px solid #e8e8e8',
                    borderRadius: 8,
                    marginBottom: 10,
                    cursor: 'pointer',
                    transition: 'all 0.2s',
                    background: selectedTaskId === task.taskId ? '#f0f7ff' : '#fff',
                  }}
                >
                  <Space size={8} style={{ marginBottom: 4 }}>
                    <Tag
                      color={task.status === 'completed' ? 'success' : task.status === 'failed' ? 'red' : 'processing'}
                      style={{ fontSize: 12, margin: 0 }}
                    >
                      {task.status}
                    </Tag>
                    <Text style={{ fontSize: 13, fontWeight: 500 }}>{task.progress ?? 0}%</Text>
                  </Space>
                  {/* 修复：用taskId替代不存在的createdAt，若你知道真实时间字段名直接替换即可 */}
                  <Text type="secondary" style={{ fontSize: 12 }}>任务ID: {task.taskId.slice(0, 8)}</Text>
                </div>
              ))}
            </Col>

            {/* 右侧日志详情 */}
            <Col span={18}>
              <div style={{ padding: '20px 22px', background: '#f7f8fa', borderRadius: 10, minHeight: 200 }}>
                {selectedTaskId && taskErrors.length > 0 ? (
                  <>
                    <Text strong style={{ fontSize: 14 }}>
                      {taskErrors[0]?.message || buildMessage || '构建详情'}
                    </Text>
                    <div style={{ marginTop: 12 }}>
                      <Text type="secondary" style={{ fontSize: 13 }}>
                        模式 {buildStage || 'incremental'} | 质量 {overview?.status || 'failed'} |
                        片段 {graphStatus?.chunkCount ?? 0} | 失败文件 {taskErrors.length}
                      </Text>
                    </div>
                    <div style={{ marginTop: 18 }}>
                      <Text strong style={{ fontSize: 13 }}>错误 / 警告</Text>
                      <ul style={{ paddingLeft: 20, marginTop: 8, color: '#4B5563', fontSize: 13, lineHeight: 1.8 }}>
                        {taskErrors.map((err, idx) => (
                          <li key={idx}>
                            {err.message}（可重试）
                          </li>
                        ))}
                      </ul>
                    </div>
                  </>
                ) : (
                  <Text type="secondary">选择左侧日志查看详情</Text>
                )}
              </div>
            </Col>
          </Row>
        </Card>

        {/* ===== 模块4：辅助信息面板【次要看板区】 ===== */}
        <Row gutter={[24, 20]}>
          <Col span={12}>
            <Card
              style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
              bodyStyle={{ padding: '18px 20px' }}
              title={<span style={{ fontWeight: 600, fontSize: 14 }}>仓库摘要</span>}
            >
              <Text type="secondary" style={{ fontSize: 13 }}>
                构建索引后自动生成
              </Text>
            </Card>
          </Col>

          <Col span={12}>
            <Card
              style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
              bodyStyle={{ padding: '18px 20px' }}
              title={<span style={{ fontWeight: 600, fontSize: 14 }}>README 预览</span>}
            >
              <Text type="secondary" style={{ fontSize: 13 }}>
                构建后显示
              </Text>
            </Card>
          </Col>

          <Col span={12}>
            <Card
              style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
              bodyStyle={{ padding: '18px 20px' }}
              title={<span style={{ fontWeight: 600, fontSize: 14 }}>项目结构</span>}
            >
              <Text type="secondary" style={{ fontSize: 13 }}>
                {overview?.fileCount ? `${overview.fileCount} 文件` : '暂无索引数据'}
              </Text>
            </Card>
          </Col>

          <Col span={12}>
            <Card
              style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
              bodyStyle={{ padding: '18px 20px' }}
              title={<span style={{ fontWeight: 600, fontSize: 14 }}>语言分布</span>}
            >
              <Text type="secondary" style={{ fontSize: 13 }}>
                构建索引后显示
              </Text>
            </Card>
          </Col>
        </Row>

        {/* ===== 模块5：索引说明【帮助区，最底部】 ===== */}
        <Card
          size="small"
          style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}
          bodyStyle={{ padding: '18px 20px' }}
          title={
            <span style={{ fontWeight: 600, fontSize: 14 }}>
              <InfoCircleOutlined style={{ marginRight: 6 }} />
              索引范围说明
            </span>
          }
        >
          <Text type="secondary" style={{ fontSize: 13, lineHeight: 1.8 }}>
            必须索引（服务问答 / Issue）：CodeWiki 支持源代码、README、OpenAPI、构建与部署配置<br />
            不索引：.git、node_modules、dist、build、.venv、target 等目录<br />
            Issue分析：需要 GraphRAG source chunks；智能问答：需要 GraphRAG source chunks、AST关系
          </Text>
        </Card>
      </div>
    </PageShell>
  )
}