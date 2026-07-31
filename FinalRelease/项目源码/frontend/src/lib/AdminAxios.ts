import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { createClient, createConfig } from '../api/generated/client'
import {
  clearAdminAuth,
  getAdminToken,
} from './adminAuth'

const API_BASE = import.meta.env.VITE_API_URL || ''

type ErrorDetailBody = {
  detail?: string | { msg?: string }[] | Record<string, unknown>
}

function formatErrorDetail(data: unknown, fallback: string): string {
  if (!data || typeof data !== 'object') return fallback
  const detail = (data as ErrorDetailBody).detail
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) {
    return detail.map((item) => item.msg ?? JSON.stringify(item)).join('; ')
  }
  if (detail && typeof detail === 'object') return JSON.stringify(detail)
  return fallback
}

export const adminAxios = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
})

adminAxios.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAdminToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

adminAxios.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ErrorDetailBody>) => {
    if (error.response?.status === 401) {
      clearAdminAuth()
      if (!window.location.pathname.startsWith('/admin/login')) {
        window.location.href = '/admin/login'
      }
      return Promise.reject(new Error('管理员未登录'))
    }
    const status = error.response?.status
    const fallback = status ? `请求失败: ${status}` : error.message || '请求失败'
    return Promise.reject(new Error(formatErrorDetail(error.response?.data, fallback)))
  },
)

/** OpenAPI SDK client that attaches the admin Bearer token. */
export const adminClient = createClient(
  createConfig({
    axios: adminAxios,
    baseURL: API_BASE,
    auth: () => getAdminToken() ?? undefined,
    throwOnError: true,
  }),
)
