import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import RepoList from '../pages/RepoList';

const mockSyncRepoList = vi.fn();
const mockNavigate = vi.fn();

const baseRepo = (overrides = {}) => ({
  id: 'repo-1',
  fullName: 'owner/repo1',
  description: 'Test repository 1',
  language: 'JavaScript',
  stars: 100,
  openIssues: 10,
  lastSync: '2 hours ago',
  private: false,
  syncStatus: 'synced' as const,
  htmlUrl: 'https://github.com/owner/repo1',
  ...overrides,
});

const defaultRepoList = [
  baseRepo(),
  baseRepo({
    id: 'repo-2',
    fullName: 'owner/repo2',
    description: 'Private repository',
    language: 'Python',
    stars: 50,
    openIssues: 5,
    lastSync: '1 day ago',
    private: true,
    syncStatus: 'error' as const,
    htmlUrl: 'https://github.com/owner/repo2',
  }),
];

const createMockContext = (overrides = {}) => ({
  repoList: defaultRepoList,
  isRepoListPending: false,
  isRepoListFetching: false,
  syncRepoList: mockSyncRepoList,
  ...overrides,
});

vi.mock('../context/RepoContext', () => ({
  useRepoContext: vi.fn(),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

vi.mock('../lib/AuthAxios', () => ({
  getUsername: () => 'test-user',
}));

import { useRepoContext } from '../context/RepoContext';

describe('RepoList Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useRepoContext).mockReturnValue(createMockContext());
    mockSyncRepoList.mockResolvedValue(undefined);
  });

  describe('Basic Rendering', () => {
    it('should render page title', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('你的仓库')).toBeInTheDocument();
      });
    });

    it('should render refresh button', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /刷新仓库/ })).toBeInTheDocument();
      });
    });

    it('should render connected GitHub account info', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText(/已连接 GitHub 账号 test-user/)).toBeInTheDocument();
      });
    });

    it('should render repo count in description', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText(/共 2 个仓库/)).toBeInTheDocument();
      });
    });

    it('should render both repo cards', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('owner/repo1')).toBeInTheDocument();
        expect(screen.getByText('owner/repo2')).toBeInTheDocument();
      });
    });

    it('should render repo descriptions', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('Test repository 1')).toBeInTheDocument();
        expect(screen.getByText('Private repository')).toBeInTheDocument();
      });
    });
  });

  describe('Sync Status', () => {
    it('should show synced tag for synced repos', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('已同步')).toBeInTheDocument();
      });
    });

    it('should show error tag for failed repos', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('失败')).toBeInTheDocument();
      });
    });

    it('should show syncing tag for in-progress repos', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ id: 'r3', fullName: 'owner/syncing', syncStatus: 'syncing' as const })],
        }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('同步中')).toBeInTheDocument();
      });
    });

    it('should show unknown tag for missing status', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ id: 'r4', fullName: 'owner/weird', syncStatus: undefined as any })],
        }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('未知')).toBeInTheDocument();
      });
    });
  });

  describe('Private Badge', () => {
    it('should show 私有 tag for private repos', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('私有')).toBeInTheDocument();
      });
    });

    it('should not show 私有 tag for public repos', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo()],
        }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('owner/repo1')).toBeInTheDocument();
      });
      expect(screen.queryByText('私有')).not.toBeInTheDocument();
    });
  });

  describe('Repo Metadata', () => {
    it('should render language', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('JavaScript')).toBeInTheDocument();
        expect(screen.getByText('Python')).toBeInTheDocument();
      });
    });

    it('should render stars count', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('100')).toBeInTheDocument();
        expect(screen.getByText('50')).toBeInTheDocument();
      });
    });

    it('should render open issues count', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('10')).toBeInTheDocument();
        expect(screen.getByText('5')).toBeInTheDocument();
      });
    });

    it('should render lastSync label', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText(/更新：2 hours ago/)).toBeInTheDocument();
      });
    });

    it('should render fallback description when missing', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ description: '' })],
        }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('暂无描述')).toBeInTheDocument();
      });
    });

    it('should hide language indicator when language is --', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ language: '--' })],
        }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('owner/repo1')).toBeInTheDocument();
      });
      expect(screen.queryByText('--')).not.toBeInTheDocument();
    });

    it('should hide lastSync when missing', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ lastSync: undefined })],
        }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('owner/repo1')).toBeInTheDocument();
      });
      expect(screen.queryByText(/更新：/)).not.toBeInTheDocument();
    });
  });

  describe('Navigation', () => {
    it('should navigate to repo detail when clicking title', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('owner/repo1')).toBeInTheDocument();
      });
      fireEvent.click(screen.getByText('owner/repo1'));
      expect(mockNavigate).toHaveBeenCalledWith('/repos/repo-1');
    });

    it('should navigate to repo detail when clicking 进入仓库 button', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        const buttons = screen.getAllByRole('button', { name: /进入仓库/ });
        expect(buttons.length).toBeGreaterThanOrEqual(1);
      });
      const buttons = screen.getAllByRole('button', { name: /进入仓库/ });
      fireEvent.click(buttons[0]);
      expect(mockNavigate).toHaveBeenCalledWith('/repos/repo-1');
    });

    it('should have GitHub link to external repo', async () => {
      const { container } = render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        const anchors = container.querySelectorAll('a');
        const githubLink = Array.from(anchors).find(
          (a) => a.getAttribute('href') === 'https://github.com/owner/repo1',
        );
        expect(githubLink).toBeTruthy();
        expect(githubLink?.getAttribute('target')).toBe('_blank');
      });
    });

    it('should hide GitHub link when htmlUrl is missing', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ htmlUrl: undefined })],
        }),
      );
      const { container } = render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText('owner/repo1')).toBeInTheDocument();
      });
      const anchors = container.querySelectorAll('a');
      const githubLink = Array.from(anchors).find(
        (a) => a.getAttribute('href') === 'https://github.com/owner/repo1',
      );
      expect(githubLink).toBeFalsy();
    });
  });

  describe('Sync Action', () => {
    it('should call syncRepoList when clicking refresh button', async () => {
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /刷新仓库/ })).toBeInTheDocument();
      });
      fireEvent.click(screen.getByRole('button', { name: /刷新仓库/ }));
      await waitFor(() => {
        expect(mockSyncRepoList).toHaveBeenCalledTimes(1);
      });
    });

    it('should disable refresh button while loading', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({ isRepoListFetching: true }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /刷新仓库/ })).toBeDisabled();
      });
    });

    it('should disable refresh button while pending', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({ isRepoListPending: true }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /刷新仓库/ })).toBeDisabled();
      });
    });

    it('should show error alert when syncRepoList throws', async () => {
      mockSyncRepoList.mockRejectedValueOnce(new Error('Sync failed'));
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /刷新仓库/ })).toBeInTheDocument();
      });
      fireEvent.click(screen.getByRole('button', { name: /刷新仓库/ }));
      await waitFor(() => {
        expect(screen.getByText('Sync failed')).toBeInTheDocument();
      });
    });

    it('should show fallback error message for non-Error throws', async () => {
      mockSyncRepoList.mockRejectedValueOnce('string error');
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /刷新仓库/ })).toBeInTheDocument();
      });
      fireEvent.click(screen.getByRole('button', { name: /刷新仓库/ }));
      await waitFor(() => {
        expect(screen.getByText('加载仓库失败')).toBeInTheDocument();
      });
    });

    it('should clear error on next successful sync', async () => {
      mockSyncRepoList.mockRejectedValueOnce(new Error('First fail'));
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      const btn = await screen.findByRole('button', { name: /刷新仓库/ });
      fireEvent.click(btn);
      await waitFor(() => {
        expect(screen.getByText('First fail')).toBeInTheDocument();
      });
      fireEvent.click(btn);
      await waitFor(() => {
        expect(screen.queryByText('First fail')).not.toBeInTheDocument();
      });
    });
  });

  describe('Loading State', () => {
    it('should show spinner while pending', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({ isRepoListPending: true, repoList: [] }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText(/加载仓库列表中/)).toBeInTheDocument();
      });
    });

    it('should show spinner while fetching', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({ isRepoListFetching: true, repoList: [] }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText(/加载仓库列表中/)).toBeInTheDocument();
      });
    });
  });

  describe('Empty State', () => {
    it('should show empty message when repo list is empty', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({ repoList: [] }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(
          screen.getByText(/暂无仓库，请确认 GitHub OAuth 权限包含 repo 读取/),
        ).toBeInTheDocument();
      });
    });

    it('should show 0 repos in description when empty', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({ repoList: [] }),
      );
      render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        expect(screen.getByText(/共 0 个仓库/)).toBeInTheDocument();
      });
    });
  });

  describe('Status Border Color', () => {
    it('should apply green border for synced repos', async () => {
      const { container } = render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        const card = container.querySelector('.repo-card-main');
        expect(card).toBeTruthy();
        const style = (card as HTMLElement).getAttribute('style') || '';
        expect(style).toMatch(/(0,\s*180,\s*42)|(00B42A)|(rgb\(0,\s*180,\s*42\))/i);
      });
    });

    it('should apply red border for error repos', async () => {
      const { container } = render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        const cards = container.querySelectorAll('.repo-card-main');
        expect(cards.length).toBe(2);
        const errorCard = cards[1] as HTMLElement;
        const style = errorCard.getAttribute('style') || '';
        expect(style).toMatch(/(245,\s*63,\s*63)|(F53F3F)|(rgb\(245,\s*63,\s*63\))/i);
      });
    });

    it('should apply blue border for syncing repos', async () => {
      vi.mocked(useRepoContext).mockReturnValue(
        createMockContext({
          repoList: [baseRepo({ id: 'r5', fullName: 'owner/syncing', syncStatus: 'syncing' as const })],
        }),
      );
      const { container } = render(
        <MemoryRouter>
          <RepoList />
        </MemoryRouter>,
      );
      await waitFor(() => {
        const card = container.querySelector('.repo-card-main') as HTMLElement;
        const style = card.getAttribute('style') || '';
        expect(style).toMatch(/(22,\s*93,\s*255)|(165DFF)|(rgb\(22,\s*93,\s*255\))/i);
      });
    });
  });
});