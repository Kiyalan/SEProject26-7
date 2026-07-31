import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import OAuthSuccess from '../pages/OAuthSuccess';

// Mock AuthAxios
vi.mock('../lib/AuthAxios', () => ({
  setAuth: vi.fn(),
}));

// Mock react-router-dom navigate
const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

describe('OAuthSuccess Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should redirect to /repos after successful token storage', async () => {
    const searchParams = new URLSearchParams();
    searchParams.set('access_token', 'test-jwt-token');
    searchParams.set('username', 'testuser');

    render(
      <MemoryRouter initialEntries={[`/oauth/success?${searchParams.toString()}`]}>
        <OAuthSuccess />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/repos', { replace: true });
    });
  });

  it('should show loading spinner while processing', () => {
    const searchParams = new URLSearchParams();
    searchParams.set('access_token', 'test-token');
    searchParams.set('username', 'testuser');

    render(
      <MemoryRouter initialEntries={[`/oauth/success?${searchParams.toString()}`]}>
        <OAuthSuccess />
      </MemoryRouter>
    );

    // Ant Design Spin uses aria-busy attribute
    const spinner = document.querySelector('[aria-busy="true"]');
    expect(spinner).toBeInTheDocument();
  });

  it('should redirect to login with error when token is missing', async () => {
    render(
      <MemoryRouter initialEntries={['/oauth/success']}>
        <OAuthSuccess />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith('/login?error=missing_token', { replace: true });
    });
  });
});
