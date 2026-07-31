import type { ChatMessage, Issue, KnowledgeNode, Repository } from '../types'

export const repositories: Repository[] = [
  {
    id: '1',
    name: 'react',
    fullName: 'facebook/react',
    description: 'The library for web and native user interfaces.',
    stars: 232000,
    openIssues: 842,
    language: 'JavaScript',
    lastSync: '2026-07-06 10:30',
    syncStatus: 'synced',
  },
  {
    id: '2',
    name: 'vite',
    fullName: 'vitejs/vite',
    description: 'Next generation frontend tooling.',
    stars: 72000,
    openIssues: 156,
    language: 'TypeScript',
    lastSync: '2026-07-06 09:15',
    syncStatus: 'synced',
  },
  {
    id: '3',
    name: 'langchain',
    fullName: 'langchain-ai/langchain',
    description: 'Build context-aware reasoning applications.',
    stars: 98000,
    openIssues: 312,
    language: 'Python',
    lastSync: '2026-07-05 18:40',
    syncStatus: 'syncing',
  },
]

export const issues: Issue[] = [
  {
    id: 'i1',
    repoId: '1',
    number: 28401,
    title: 'How to use useEffect with async functions?',
    body: 'I keep getting warnings when I put async directly inside useEffect. What is the recommended pattern?',
    author: 'dev_newbie',
    createdAt: '2026-07-05',
    labels: ['question', 'documentation'],
    type: 'usage_question',
    aiSummary: '用户使用问题：询问 useEffect 中正确使用异步函数的方式。',
    suggestedReply:
      'Please avoid making the useEffect callback async. Instead, define an async function inside the effect and call it. See the docs on synchronizing with effects.',
    confidence: 0.92,
  },
  {
    id: 'i2',
    repoId: '1',
    number: 28388,
    title: 'Duplicate: StrictMode double render in development',
    body: 'My component renders twice in dev mode. Is this a bug?',
    author: 'confused_user',
    createdAt: '2026-07-04',
    labels: ['question'],
    type: 'duplicate',
    aiSummary: '重复问题：与 #28102 描述相同，属于 StrictMode 预期行为。',
    suggestedReply:
      'This is expected in React StrictMode during development. Closing as duplicate of #28102.',
    confidence: 0.88,
  },
  {
    id: 'i3',
    repoId: '2',
    number: 18920,
    title: 'Build fails on Windows with ENOENT error',
    body: 'Steps: clone repo, npm install, npm run build. Error log attached partially...',
    author: 'win_dev',
    createdAt: '2026-07-06',
    labels: ['bug', 'windows'],
    type: 'bug_fix',
    aiSummary: '缺陷修复：Windows 路径处理导致构建失败，可能涉及 rollup 配置。',
    suggestedReply:
      'Thanks for the report. Could you provide the full error log and your Node.js version?',
    confidence: 0.76,
  },
  {
    id: 'i4',
    repoId: '2',
    number: 18905,
    title: 'Add support for custom HMR overlay theme',
    body: 'It would be helpful if teams could brand the error overlay.',
    author: 'design_lead',
    createdAt: '2026-07-03',
    labels: ['enhancement'],
    type: 'feature_request',
    aiSummary: '功能改进：请求支持自定义 HMR 错误遮罩主题。',
    suggestedReply:
      'Interesting idea. We are tracking UI customization requests in the roadmap discussion.',
    confidence: 0.81,
  },
  {
    id: 'i5',
    repoId: '3',
    number: 12044,
    title: 'Crash when API key missing',
    body: 'App crashes without clear message.',
    author: 'ai_builder',
    createdAt: '2026-07-06',
    labels: ['bug'],
    type: 'insufficient_info',
    aiSummary: '信息不充分：缺少复现步骤、SDK 版本与完整堆栈。',
    suggestedReply:
      'Please share your langchain version, provider setup, and the full stack trace so we can reproduce.',
    confidence: 0.95,
  },
]

export const chatHistory: ChatMessage[] = [
  {
    id: 'm1',
    role: 'user',
    content: '这个项目的入口文件在哪里？',
    questionType: 'where',
  },
  {
    id: 'm2',
    role: 'assistant',
    content:
      'Vite 项目的入口通常是 `src/main.tsx`，它挂载 React 根组件。构建配置在 `vite.config.ts`。',
    citations: [
      { file: 'src/main.tsx', line: 1 },
      { file: 'vite.config.ts', line: 1 },
    ],
    questionType: 'where',
  },
  {
    id: 'm3',
    role: 'user',
    content: 'Router 是怎么组织的？',
    questionType: 'how',
  },
  {
    id: 'm4',
    role: 'assistant',
    content:
      '路由在 `App.tsx` 中通过 React Router 定义，主布局 `MainLayout` 包裹业务页面，各功能模块对应独立 page 组件。',
    citations: [{ file: 'src/App.tsx', line: 12 }],
    questionType: 'how',
  },
]

export const knowledgeTree: KnowledgeNode[] = [
  {
    key: 'src',
    title: 'src',
    type: 'folder',
    children: [
      {
        key: 'src/pages',
        title: 'pages',
        type: 'module',
        children: [
          { key: 'src/pages/Chat.tsx', title: 'Chat.tsx', type: 'file' },
          { key: 'src/pages/IssueList.tsx', title: 'IssueList.tsx', type: 'file' },
        ],
      },
      {
        key: 'src/layouts',
        title: 'layouts',
        type: 'module',
        children: [{ key: 'src/layouts/MainLayout.tsx', title: 'MainLayout.tsx', type: 'file' }],
      },
      { key: 'src/mock', title: 'mock', type: 'module' },
    ],
  },
  {
    key: 'docs',
    title: 'docs',
    type: 'folder',
    children: [
      { key: 'docs/VISION.md', title: 'VISION.md', type: 'file' },
      { key: 'docs/USE_CASES.md', title: 'USE_CASES.md', type: 'file' },
    ],
  },
]

export const issueTypeLabels: Record<string, { label: string; color: string }> = {
  usage_question: { label: '使用问题', color: 'blue' },
  duplicate: { label: '重复问题', color: 'default' },
  insufficient_info: { label: '信息不足', color: 'orange' },
  bug_fix: { label: '缺陷修复', color: 'red' },
  feature_request: { label: '功能改进', color: 'green' },
}
