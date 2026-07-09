import { Navigate, Route, Routes } from 'react-router-dom'
import ProtectedRoute from './components/ProtectedRoute'
import MainLayout from './layouts/MainLayout'
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
