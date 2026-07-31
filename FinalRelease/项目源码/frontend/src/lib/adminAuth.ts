const ADMIN_TOKEN_KEY = 'repopilot_admin_token'
const ADMIN_USERNAME_KEY = 'repopilot_admin_username'
const ADMIN_ROLE_KEY = 'repopilot_admin_role'
const LOCK_UNTIL_KEY = 'repopilot_admin_lock_until'
const FAIL_COUNT_KEY = 'repopilot_admin_fail_count'

/** 演示账号（与后端 repopilot.admin 默认一致） */
export const DEMO_ADMIN = {
  username: 'admin',
  password: 'repopilot2026',
  displayName: '系统管理员',
  role: 'super_admin' as const,
}

const MAX_ATTEMPTS = 5
const LOCK_MINUTES = 15
const API_BASE = import.meta.env.VITE_API_URL || ''

export type AdminRole = 'super_admin' | 'ops_admin'

export function getAdminToken(): string | null {
  return localStorage.getItem(ADMIN_TOKEN_KEY)
}

export function getAdminUsername(): string | null {
  return localStorage.getItem(ADMIN_USERNAME_KEY)
}

export function getAdminRole(): AdminRole | null {
  const role = localStorage.getItem(ADMIN_ROLE_KEY)
  return role === 'super_admin' || role === 'ops_admin' ? role : null
}

export function isAdminAuthenticated(): boolean {
  return Boolean(getAdminToken())
}

export function setAdminAuth(username: string, role: AdminRole, token: string) {
  localStorage.setItem(ADMIN_TOKEN_KEY, token)
  localStorage.setItem(ADMIN_USERNAME_KEY, username)
  localStorage.setItem(ADMIN_ROLE_KEY, role)
  localStorage.removeItem(FAIL_COUNT_KEY)
  localStorage.removeItem(LOCK_UNTIL_KEY)
}

export function clearAdminAuth() {
  localStorage.removeItem(ADMIN_TOKEN_KEY)
  localStorage.removeItem(ADMIN_USERNAME_KEY)
  localStorage.removeItem(ADMIN_ROLE_KEY)
}

export type AdminLoginResult =
  | { ok: true }
  | { ok: false; reason: 'invalid_credentials'; remaining: number }
  | { ok: false; reason: 'locked'; minutesLeft: number }
  | { ok: false; reason: 'empty_fields' }
  | { ok: false; reason: 'network'; message: string }

function getFailCount(): number {
  return Number(localStorage.getItem(FAIL_COUNT_KEY) || '0')
}

function getLockUntil(): number {
  return Number(localStorage.getItem(LOCK_UNTIL_KEY) || '0')
}

export function getAdminLockState(): { locked: boolean; minutesLeft: number } {
  const until = getLockUntil()
  if (!until || Date.now() >= until) {
    return { locked: false, minutesLeft: 0 }
  }
  return {
    locked: true,
    minutesLeft: Math.ceil((until - Date.now()) / 60000),
  }
}

function registerFailure(): AdminLoginResult {
  const fails = getFailCount() + 1
  localStorage.setItem(FAIL_COUNT_KEY, String(fails))
  if (fails >= MAX_ATTEMPTS) {
    const until = Date.now() + LOCK_MINUTES * 60 * 1000
    localStorage.setItem(LOCK_UNTIL_KEY, String(until))
    localStorage.setItem(FAIL_COUNT_KEY, '0')
    return { ok: false, reason: 'locked', minutesLeft: LOCK_MINUTES }
  }
  return { ok: false, reason: 'invalid_credentials', remaining: MAX_ATTEMPTS - fails }
}

export async function adminLogin(username: string, password: string): Promise<AdminLoginResult> {
  if (!username.trim() || !password.trim()) {
    return { ok: false, reason: 'empty_fields' }
  }

  const lock = getAdminLockState()
  if (lock.locked) {
    return { ok: false, reason: 'locked', minutesLeft: lock.minutesLeft }
  }

  try {
    const response = await fetch(`${API_BASE}/api/admin/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.trim(), password }),
    })
    if (!response.ok) {
      return registerFailure()
    }
    const data = (await response.json()) as {
      token?: string
      username?: string
      role?: AdminRole
    }
    if (!data.token) {
      return registerFailure()
    }
    setAdminAuth(
      data.username || DEMO_ADMIN.displayName,
      data.role === 'ops_admin' ? 'ops_admin' : 'super_admin',
      data.token,
    )
    return { ok: true }
  } catch (err) {
    return {
      ok: false,
      reason: 'network',
      message: err instanceof Error ? err.message : '无法连接后端',
    }
  }
}
