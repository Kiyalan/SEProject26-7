import { Navigate, Route, Routes } from 'react-router-dom'
import AdminProtectedRoute from './components/AdminProtectedRoute'
import ProtectedRoute from './components/ProtectedRoute'
import AdminLayout from './layouts/AdminLayout'
import MainLayout from './layouts/MainLayout'
import AdminAuditLogs from './pages/admin/AdminAuditLogs'
import AdminDashboard from './pages/admin/AdminDashboard'
import AdminDataIntegrity from './pages/admin/AdminDataIntegrity'
import AdminFaqExport from './pages/admin/AdminFaqExport'
import AdminLogin from './pages/admin/AdminLogin'
import AdminSyncFailures from './pages/admin/AdminSyncFailures'
import AdminSyncLogs from './pages/admin/AdminSyncLogs'
import AdminUsers from './pages/admin/AdminUsers'
import Chat from './pages/Chat'
import IssueDetail from './pages/IssueDetail'
import IssueList from './pages/IssueList'
import Knowledge from './pages/Knowledge'
import Login from './pages/Login'
import OAuthSuccess from './pages/OAuthSuccess'
import RepoDetail from './pages/RepoDetail'
import RepoList from './pages/RepoList'
import Settings from './pages/Settings'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/oauth/success" element={<OAuthSuccess />} />
      <Route path="/admin/login" element={<AdminLogin />} />
      <Route element={<AdminProtectedRoute />}>
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<AdminDashboard />} />
          <Route path="sync-logs" element={<AdminSyncLogs />} />
          <Route path="data-integrity" element={<AdminDataIntegrity />} />
          <Route path="sync-failures" element={<AdminSyncFailures />} />
          <Route path="faq-export" element={<AdminFaqExport />} />
          <Route path="users" element={<AdminUsers />} />
          <Route path="audit-logs" element={<AdminAuditLogs />} />
        </Route>
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<Navigate to="/repos" replace />} />
          <Route path="repos" element={<RepoList />} />
          <Route path="repos/:repoId" element={<RepoDetail />} />
          <Route path="chat" element={<Chat />} />
          <Route path="issues" element={<IssueList />} />
          <Route path="issues/:repoId/:issueNumber" element={<IssueDetail />} />
          <Route path="knowledge" element={<Knowledge />} />
          <Route path="settings" element={<Settings />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
