import { Navigate, Outlet } from 'react-router-dom'
import { isAdminAuthenticated } from '../lib/adminAuth'

export default function AdminProtectedRoute() {
  if (!isAdminAuthenticated()) {
    return <Navigate to="/admin/login" replace />
  }
  return <Outlet />
}
