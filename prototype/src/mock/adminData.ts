export type SyncStatus = 'success' | 'running' | 'failed' | 'paused'

export interface PlatformStats {
  totalRepos: number
  syncedRepos: number
  failedRepos: number
  knowledgeChunks: number
  memoryEntries: number
  faqEntries: number
  activeUsers: number
  openIssues: number
  syncSuccessRate: number
  lastFullCheck: string
}

export interface SyncTaskLog {
  id: string
  repoFullName: string
  owner: string
  status: SyncStatus
  startedAt: string
  endedAt: string | null
  filesSynced: number
  errorMessage: string | null
  trigger: 'manual' | 'webhook' | 'scheduled'
}

export interface IntegrityCheck {
  repoFullName: string
  knowledgeOk: boolean
  memoryOk: boolean
  faqOk: boolean
  chunkCount: number
  memoryCount: number
  lastChecked: string
  issues: string[]
}

export interface SyncFailure {
  id: string
  repoFullName: string
  failedAt: string
  errorType: 'network' | 'auth' | 'rate_limit' | 'webhook' | 'parse'
  errorMessage: string
  retryCount: number
  status: 'pending' | 'retrying' | 'ignored'
}

export interface CommunityUser {
  id: string
  login: string
  email: string
  boundRepos: number
  status: 'active' | 'suspended'
  lastLogin: string
  createdAt: string
}

export interface AuditLog {
  id: string
  admin: string
  action: string
  target: string
  result: 'success' | 'failed'
  createdAt: string
}

export interface FaqRepoOption {
  repoFullName: string
  faqCount: number
  memoryCount: number
  lastUpdated: string
}

export const platformStats: PlatformStats = {
  totalRepos: 24,
  syncedRepos: 21,
  failedRepos: 3,
  knowledgeChunks: 18420,
  memoryEntries: 312,
  faqEntries: 48,
  activeUsers: 17,
  openIssues: 56,
  syncSuccessRate: 87.5,
  lastFullCheck: '2026-07-12T14:30:00+08:00',
}

export const syncTaskLogs: SyncTaskLog[] = [
  {
    id: 'sync-001',
    repoFullName: 'Kiyalan/SEProject26-7',
    owner: 'Kiyalan',
    status: 'success',
    startedAt: '2026-07-12T13:00:00+08:00',
    endedAt: '2026-07-12T13:04:22+08:00',
    filesSynced: 186,
    errorMessage: null,
    trigger: 'manual',
  },
  {
    id: 'sync-002',
    repoFullName: 'Yu-Liang-Yan/repopilot-xiaoxueqi',
    owner: 'Yu-Liang-Yan',
    status: 'running',
    startedAt: '2026-07-12T14:10:00+08:00',
    endedAt: null,
    filesSynced: 92,
    errorMessage: null,
    trigger: 'scheduled',
  },
  {
    id: 'sync-003',
    repoFullName: 'octocat/Hello-World',
    owner: 'octocat',
    status: 'failed',
    startedAt: '2026-07-12T11:20:00+08:00',
    endedAt: '2026-07-12T11:21:05+08:00',
    filesSynced: 0,
    errorMessage: 'GitHub API rate limit exceeded',
    trigger: 'webhook',
  },
  {
    id: 'sync-004',
    repoFullName: 'facebook/react',
    owner: 'facebook',
    status: 'success',
    startedAt: '2026-07-12T09:00:00+08:00',
    endedAt: '2026-07-12T09:18:44+08:00',
    filesSynced: 2841,
    errorMessage: null,
    trigger: 'scheduled',
  },
  {
    id: 'sync-005',
    repoFullName: 'vercel/next.js',
    owner: 'vercel',
    status: 'paused',
    startedAt: '2026-07-12T08:30:00+08:00',
    endedAt: null,
    filesSynced: 1200,
    errorMessage: '仓库体量过大，等待分批续传',
    trigger: 'manual',
  },
]

export const integrityChecks: IntegrityCheck[] = [
  {
    repoFullName: 'Kiyalan/SEProject26-7',
    knowledgeOk: true,
    memoryOk: true,
    faqOk: true,
    chunkCount: 842,
    memoryCount: 28,
    lastChecked: '2026-07-12T14:30:00+08:00',
    issues: [],
  },
  {
    repoFullName: 'Yu-Liang-Yan/repopilot-xiaoxueqi',
    knowledgeOk: true,
    memoryOk: false,
    faqOk: true,
    chunkCount: 1204,
    memoryCount: 0,
    lastChecked: '2026-07-12T14:30:00+08:00',
    issues: ['长期记忆库为空，建议积累更多问答后触发聚类'],
  },
  {
    repoFullName: 'octocat/Hello-World',
    knowledgeOk: false,
    memoryOk: false,
    faqOk: false,
    chunkCount: 0,
    memoryCount: 0,
    lastChecked: '2026-07-12T14:30:00+08:00',
    issues: ['同步失败导致知识库未构建', 'FAQ 库未初始化'],
  },
]

export const syncFailures: SyncFailure[] = [
  {
    id: 'fail-001',
    repoFullName: 'octocat/Hello-World',
    failedAt: '2026-07-12T11:21:05+08:00',
    errorType: 'rate_limit',
    errorMessage: 'GitHub API rate limit exceeded (403)',
    retryCount: 2,
    status: 'pending',
  },
  {
    id: 'fail-002',
    repoFullName: 'private-org/internal-tools',
    failedAt: '2026-07-12T10:05:00+08:00',
    errorType: 'auth',
    errorMessage: '用户 OAuth Token 已失效，需重新授权',
    retryCount: 0,
    status: 'pending',
  },
  {
    id: 'fail-003',
    repoFullName: 'demo/webhook-test',
    failedAt: '2026-07-11T22:40:00+08:00',
    errorType: 'webhook',
    errorMessage: 'Webhook 回调地址未配置或签名验证失败',
    retryCount: 1,
    status: 'retrying',
  },
]

export const communityUsers: CommunityUser[] = [
  {
    id: 'u-001',
    login: 'Yu-Liang-Yan',
    email: 'yu***@example.com',
    boundRepos: 3,
    status: 'active',
    lastLogin: '2026-07-12T13:45:00+08:00',
    createdAt: '2026-06-20T10:00:00+08:00',
  },
  {
    id: 'u-002',
    login: 'Kiyalan',
    email: 'ki***@example.com',
    boundRepos: 5,
    status: 'active',
    lastLogin: '2026-07-12T09:12:00+08:00',
    createdAt: '2026-06-18T08:30:00+08:00',
  },
  {
    id: 'u-003',
    login: 'spam_bot',
    email: 'sp***@example.com',
    boundRepos: 0,
    status: 'suspended',
    lastLogin: '2026-07-01T16:00:00+08:00',
    createdAt: '2026-07-01T15:00:00+08:00',
  },
]

export const auditLogs: AuditLog[] = [
  {
    id: 'log-001',
    admin: '系统管理员',
    action: '全平台数据完整性校验',
    target: '全部仓库',
    result: 'success',
    createdAt: '2026-07-12T14:30:00+08:00',
  },
  {
    id: 'log-002',
    admin: '系统管理员',
    action: '导出 FAQ 文档',
    target: 'Kiyalan/SEProject26-7',
    result: 'success',
    createdAt: '2026-07-12T11:00:00+08:00',
  },
  {
    id: 'log-003',
    admin: '系统管理员',
    action: '重试同步任务',
    target: 'octocat/Hello-World',
    result: 'failed',
    createdAt: '2026-07-12T11:25:00+08:00',
  },
]

export const faqRepoOptions: FaqRepoOption[] = [
  {
    repoFullName: 'Kiyalan/SEProject26-7',
    faqCount: 18,
    memoryCount: 28,
    lastUpdated: '2026-07-12T10:00:00+08:00',
  },
  {
    repoFullName: 'Yu-Liang-Yan/repopilot-xiaoxueqi',
    faqCount: 12,
    memoryCount: 15,
    lastUpdated: '2026-07-11T18:30:00+08:00',
  },
  {
    repoFullName: 'facebook/react',
    faqCount: 18,
    memoryCount: 42,
    lastUpdated: '2026-07-10T09:00:00+08:00',
  },
]

export const healthTrend = [
  { date: '07-06', syncRate: 82, activeUsers: 9 },
  { date: '07-07', syncRate: 85, activeUsers: 11 },
  { date: '07-08', syncRate: 84, activeUsers: 12 },
  { date: '07-09', syncRate: 88, activeUsers: 14 },
  { date: '07-10', syncRate: 86, activeUsers: 15 },
  { date: '07-11', syncRate: 89, activeUsers: 16 },
  { date: '07-12', syncRate: 87.5, activeUsers: 17 },
]
