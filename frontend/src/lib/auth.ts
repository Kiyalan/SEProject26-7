import { projectDisplayNameCamel } from '../config/BaseConfig'

const TOKEN_KEY = `${projectDisplayNameCamel}GithubToken`
const USERNAME_KEY = `${projectDisplayNameCamel}GithubUsername`

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
