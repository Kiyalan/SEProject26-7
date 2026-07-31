import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { projectDisplayNameCamel } from '../config/BaseConfig'
import type { CreateClientConfig } from '../api/generated/client.gen'

const TOKEN_KEY = `${projectDisplayNameCamel}GithubToken`
const USERNAME_KEY = `${projectDisplayNameCamel}GithubUsername`

const API_BASE = import.meta.env.VITE_API_URL || ''

type ErrorDetailBody = {
  detail?: string | { msg?: string }[] | Record<string, unknown>
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getUsername(): string | null {
  return localStorage.getItem(USERNAME_KEY)
}

export function setAuth(token: string, username: string) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USERNAME_KEY, username)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
}

export function isAuthenticated(): boolean {
  return Boolean(getToken())
}

export function startGithubLogin() {
  window.location.href = '/auth/github'
}

function formatErrorDetail(data: unknown, fallback: string): string {
  if (!data || typeof data !== 'object') {
    return fallback
  }
  const detail = (data as ErrorDetailBody).detail
  if (typeof detail === 'string') {
    return detail
  }
  if (Array.isArray(detail)) {
    return detail.map((item) => item.msg ?? JSON.stringify(item)).join('; ')
  }
  if (detail && typeof detail === 'object') {
    return JSON.stringify(detail)
  }
  return fallback
}

/** Authorized Axios instance used by the openapi-ts generated client. */
export const authAxios = axios.create({
  baseURL: API_BASE,
  headers: {
    'Content-Type': 'application/json',
  },
})

authAxios.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

authAxios.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ErrorDetailBody>) => {
    if (error.response?.status === 401) {
      clearAuth()
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
      return Promise.reject(new Error('未登录'))
    }

    const status = error.response?.status
    const fallback = status ? `请求失败: ${status}` : error.message || '请求失败'
    const message = formatErrorDetail(error.response?.data, fallback)
    return Promise.reject(new Error(message))
  },
)

/**
 * Wired into openapi-ts via `runtimeConfigPath`.
 * Ensures the generated client uses {@link authAxios} from the first request.
 */
export const createClientConfig: CreateClientConfig = (config) => ({
  ...config,
  axios: authAxios,
  baseURL: API_BASE,
  auth: () => getToken() ?? undefined,
  throwOnError: true,
})
