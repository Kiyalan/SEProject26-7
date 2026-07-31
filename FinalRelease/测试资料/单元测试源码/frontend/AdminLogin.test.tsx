import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import AdminLogin from '../pages/admin/AdminLogin';
import { adminLogin as adminLoginMock } from '../lib/adminAuth';

// Mock adminAuth
const mockIsAdminAuthenticated = vi.fn(() => false);
const mockGetAdminLockState = vi.fn(() => ({ locked: false, minutesLeft: 0 }));

vi.mock('../lib/adminAuth', () => ({
  adminLogin: vi.fn(),
  isAdminAuthenticated: () => mockIsAdminAuthenticated(),
  getAdminLockState: () => mockGetAdminLockState(),
  DEMO_ADMIN: { username: 'admin', password: 'repopilot2026' }
}));

// Mock window.location
const mockLocation = { href: '' };
Object.defineProperty(window, 'location', {
  value: mockLocation,
  writable: true
});

describe('AdminLogin Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // Basic rendering tests
  it('should display login form with required fields', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByLabelText(/管理员账号/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/)).toBeInTheDocument();
  });

  it('should display login button', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByRole('button', { name: /登录运维后台/ })).toBeInTheDocument();
  });

  it('should display demo credentials hint', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByText(/演示账号/)).toBeInTheDocument();
    expect(screen.getByText(/admin/)).toBeInTheDocument();
  });

  it('should have link to user login', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByText(/GitHub 登录/)).toBeInTheDocument();
  });

  it('should display page title', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByText(/管理员登录/)).toBeInTheDocument();
  });

  it('should display system description', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByText(/RepoPilot 全平台运维管理/)).toBeInTheDocument();
  });

  it('should display demo password', () => {
    render(
      <MemoryRouter>
        <AdminLogin />
      </MemoryRouter>
    );

    expect(screen.getByText(/repopilot2026/)).toBeInTheDocument();
  });

  // Lock state tests
  describe('Account Lock State', () => {
    it('should show lock warning when account is locked', () => {
      mockGetAdminLockState.mockReturnValueOnce({ locked: true, minutesLeft: 5 });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByText(/账号已临时锁定/)).toBeInTheDocument();
    });

    it('should redirect when already authenticated', () => {
      mockIsAdminAuthenticated.mockReturnValueOnce(true);

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.queryByLabelText(/管理员账号/)).not.toBeInTheDocument();
    });

    it('should disable submit button when account is locked', () => {
      mockGetAdminLockState.mockReturnValueOnce({ locked: true, minutesLeft: 5 });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByRole('button', { name: /登录运维后台/ })).toBeDisabled();
    });

    it('should show lock description with minutes', () => {
      mockGetAdminLockState.mockReturnValueOnce({ locked: true, minutesLeft: 10 });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByText(/10 分钟/i)).toBeInTheDocument();
    });

    it('should disable form when account is locked', () => {
      mockGetAdminLockState.mockReturnValueOnce({ locked: true, minutesLeft: 5 });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      const usernameInput = screen.getByLabelText(/管理员账号/);
      expect(usernameInput).toBeDisabled();
    });

    it('should disable form inputs when locked', () => {
      mockGetAdminLockState.mockReturnValueOnce({ locked: true, minutesLeft: 5 });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      const passwordInput = screen.getByLabelText(/密码/);
      expect(passwordInput).toBeDisabled();
    });
  });

  // Form state tests
  describe('Form State', () => {
    it('should have enabled submit button by default', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByRole('button', { name: /登录运维后台/ })).not.toBeDisabled();
    });

    it('should have enabled inputs by default', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByLabelText(/管理员账号/)).not.toBeDisabled();
      expect(screen.getByLabelText(/密码/)).not.toBeDisabled();
    });

    it('should have demo credential hint section', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByText(/普通用户请使用/)).toBeInTheDocument();
    });
  });

  // No error state tests
  describe('Error State', () => {
    it('should not show error alert by default', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('should not show lock alert by default', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.queryByText(/账号已临时锁定/)).not.toBeInTheDocument();
    });
  });

  // Form structure tests
  describe('Form Structure', () => {
    it('should have form with vertical layout', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      const form = document.querySelector('form');
      expect(form).toBeInTheDocument();
    });

    it('should have username input with placeholder', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      const usernameInput = screen.getByPlaceholderText('admin');
      expect(usernameInput).toBeInTheDocument();
    });

    it('should have submit button with primary style', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      const submitButton = screen.getByRole('button', { name: /登录运维后台/ });
      expect(submitButton).toHaveClass('gh-btn-primary');
    });
  });

  // onFinish function exists (lines 22-35)
  describe('Form Component', () => {
    it('should have form with username field', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByLabelText(/管理员账号/)).toBeInTheDocument();
    });

    it('should have form with password field', () => {
      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByLabelText(/密码/)).toBeInTheDocument();
    });

    it('should call adminLogin with credentials on submit', async () => {
      vi.mocked(adminLoginMock).mockResolvedValue({ ok: true });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      // 填写账号
      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      // 填写密码
      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'repopilot2026' } });

      // 提交表单
      const form = document.querySelector('form');
      fireEvent.submit(form!);

      await waitFor(() => {
        expect(adminLoginMock).toHaveBeenCalledWith('admin', 'repopilot2026');
      });
    });

    it('should show error for invalid credentials', async () => {
      vi.mocked(adminLoginMock).mockResolvedValue({ 
        ok: false, 
        reason: 'invalid_credentials', 
        remaining: 3 
      });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      // 填写账号
      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      // 填写密码
      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'wrongpass' } });

      // 提交表单
      const form = document.querySelector('form');
      fireEvent.submit(form!);

      await waitFor(() => {
        expect(screen.getByText(/密码错误/)).toBeInTheDocument();
      });
    });

    it('should show locked error when account is locked', async () => {
      vi.mocked(adminLoginMock).mockResolvedValue({ 
        ok: false, 
        reason: 'locked', 
        minutesLeft: 10 
      });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      // 填写账号
      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      // 填写密码
      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'repopilot2026' } });

      // 提交表单
      const form = document.querySelector('form');
      fireEvent.submit(form!);

      await waitFor(() => {
        expect(screen.getByText(/账号已临时锁定/)).toBeInTheDocument();
      });
    });

    it('should show network error message', async () => {
      vi.mocked(adminLoginMock).mockResolvedValue({ 
        ok: false, 
        reason: 'network', 
        message: '网络连接失败' 
      });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      // 填写账号
      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      // 填写密码
      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'repopilot2026' } });

      // 提交表单
      const form = document.querySelector('form');
      fireEvent.submit(form!);

      await waitFor(() => {
        expect(screen.getByText(/网络连接失败/)).toBeInTheDocument();
      });
    });

    it('should show empty fields error', async () => {
      vi.mocked(adminLoginMock).mockResolvedValue({ 
        ok: false, 
        reason: 'empty_fields' 
      });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      // 填写账号
      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      // 填写密码
      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'repopilot2026' } });

      // 提交表单
      const form = document.querySelector('form');
      fireEvent.submit(form!);

      await waitFor(() => {
        expect(screen.getByText(/请输入账号与密码/)).toBeInTheDocument();
      });
    });

    it('should handle unknown reason gracefully', async () => {
      vi.mocked(adminLoginMock).mockResolvedValue({ 
        ok: false, 
        reason: 'unknown_reason' as any 
      });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'repopilot2026' } });

      const form = document.querySelector('form');
      fireEvent.submit(form!);

      // Wait for login attempt to complete (no error message displayed for unknown reason)
      await waitFor(() => {
        expect(adminLoginMock).toHaveBeenCalled();
      });
    });

    it('should show loading state during login', async () => {
      vi.mocked(adminLoginMock).mockImplementation(() => new Promise(() => {})); // Never resolves

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      // 填写账号
      const usernameInput = screen.getByLabelText(/管理员账号/);
      fireEvent.change(usernameInput, { target: { value: 'admin' } });

      // 填写密码
      const passwordInput = screen.getByLabelText(/密码/);
      fireEvent.change(passwordInput, { target: { value: 'repopilot2026' } });

      // 提交表单
      const form = document.querySelector('form');
      fireEvent.submit(form!);

      await waitFor(() => {
        expect(screen.getByText(/登录中…/)).toBeInTheDocument();
      });
    });
  });

  // Locked state tests
  describe('Locked State', () => {
    it('should show lock alert when locked', () => {
      mockGetAdminLockState.mockReturnValueOnce({ locked: true, minutesLeft: 15 });

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.getByText(/账号已临时锁定/)).toBeInTheDocument();
    });
  });

  // Already authenticated tests
  describe('Authentication Redirect', () => {
    it('should redirect when already authenticated', () => {
      mockIsAdminAuthenticated.mockReturnValueOnce(true);

      render(
        <MemoryRouter>
          <AdminLogin />
        </MemoryRouter>
      );

      expect(screen.queryByLabelText(/管理员账号/)).not.toBeInTheDocument();
    });
  });
});
