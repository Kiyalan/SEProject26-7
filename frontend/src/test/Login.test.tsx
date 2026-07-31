import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import Login from '../pages/Login';

// Mock AuthAxios
const mockIsAuthenticated = vi.fn(() => false);

vi.mock('../lib/AuthAxios', () => ({
  isAuthenticated: () => mockIsAuthenticated(),
  startGithubLogin: vi.fn(),
  getUsername: () => 'testuser'
}));

describe('Login Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsAuthenticated.mockReturnValue(false);
  });

  describe('TC-001: GitHub OAuth Login', () => {
    it('should redirect to repos when already authenticated', () => {
      mockIsAuthenticated.mockReturnValue(true);

      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      // Should redirect (Navigate component)
      expect(screen.queryByText(/使用 GitHub 登录/i)).not.toBeInTheDocument();
    });
    it('should display GitHub login button', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/使用 GitHub 登录/i)).toBeInTheDocument();
    });

    it('should show project name in title', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/Sign in to/i)).toBeInTheDocument();
    });

    it('should have GitHub icon', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      const githubIcon = document.querySelector('.octicon-mark-github');
      expect(githubIcon).toBeInTheDocument();
    });

    it('should call startGithubLogin on button click', async () => {
      const { startGithubLogin } = await import('../lib/AuthAxios');

      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      const loginButton = screen.getByRole('button', { name: /使用 GitHub 登录/i });
      fireEvent.click(loginButton);

      expect(startGithubLogin).toHaveBeenCalledTimes(1);
    });
  });

  describe('TC-002: Authorization Rejection', () => {
    it('should show error message when authorization fails', () => {
      render(
        <MemoryRouter initialEntries={['/login?error=user_cancelled']}>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/GitHub 授权失败/i)).toBeInTheDocument();
    });

    it('should display error description from URL', () => {
      render(
        <MemoryRouter initialEntries={['/login?error=redirect_uri_mismatch']}>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/GitHub 授权失败/i)).toBeInTheDocument();
      expect(screen.getByText(/redirect_uri_mismatch/i)).toBeInTheDocument();
    });
  });

  describe('TC-003: OAuth Configuration Missing', () => {
    it('should show OAuth configuration hint', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/首次使用需在 backend\/.env 配置/i)).toBeInTheDocument();
    });

    it('should explain required permissions', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/授权后可读取仓库列表/)).toBeInTheDocument();
    });
  });

  describe('TC-005: User Logout Link', () => {
    it('should have admin login link', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/运维后台登录/i)).toBeInTheDocument();
    });
  });

  describe('Usability', () => {
    it('should have primary action button', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      const loginButton = screen.getByRole('button', { name: /使用 GitHub 登录/i });
      expect(loginButton).toHaveClass('gh-btn-primary');
    });

    it('should display project description', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      expect(screen.getByText(/GitHub 仓库问答与 Issue 分析系统/)).toBeInTheDocument();
    });

    it('should have accessible heading hierarchy', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      const h1 = screen.getByRole('heading', { level: 1 });
      expect(h1).toBeInTheDocument();
    });

    it('should have proper links with href attributes', () => {
      render(
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      );

      const adminLink = screen.getByText(/运维后台登录/);
      expect(adminLink.closest('a')).toHaveAttribute('href', '/admin/login');
    });
  });
});
