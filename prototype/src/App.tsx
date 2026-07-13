import { Navigate, Route, Routes } from 'react-router-dom'
import AdminProtectedRoute from './components/AdminProtectedRoute'
import AdminLayout from './layouts/AdminLayout'
import AdminAuditLogs from './pages/admin/AdminAuditLogs'
import AdminDashboard from './pages/admin/AdminDashboard'
import AdminDataIntegrity from './pages/admin/AdminDataIntegrity'
import AdminFaqExport from './pages/admin/AdminFaqExport'
import AdminLogin from './pages/admin/AdminLogin'
import AdminSyncFailures from './pages/admin/AdminSyncFailures'
import AdminSyncLogs from './pages/admin/AdminSyncLogs'
import AdminUsers from './pages/admin/AdminUsers'
import Login from './pages/Login'

/**
 * 本分支仅包含管理员模块路由。
 * 用户端业务路由（repos/chat/issues 等）由 prototype 主体 PR 合入 main 后合并 App.tsx。
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
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
      <Route path="/" element={<Navigate to="/login" replace />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
