import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import Chat from '../pages/Chat';

// ===== Mocks =====

const mockUseRepoContext = vi.fn();
const mockFetchLlmConfig = vi.fn();
const mockFetchKnowledge = vi.fn();
const mockGetToken = vi.fn(() => 'test-token');

vi.mock('../context/RepoContext', () => ({
  useRepoContext: () => mockUseRepoContext(),
}));

vi.mock('../api/generated', () => ({
  fetchLlmConfig: (...args: unknown[]) => mockFetchLlmConfig(...args),
  fetchKnowledge: (...args: unknown[]) => mockFetchKnowledge(...args),
}));

vi.mock('../lib/AuthAxios', () => ({
  authAxios: {
    post: vi.fn().mockResolvedValue({ data: {} }),
  },
  getToken: () => mockGetToken(),
}));

const buildRepoContext = (overrides = {}) => ({
  currentRepoId: '',
  repoList: [],
  setCurrentRepo: vi.fn(),
  ...overrides,
});

describe('Chat Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    mockUseRepoContext.mockReturnValue(buildRepoContext());
    mockFetchLlmConfig.mockResolvedValue({ data: { apiKey: '' } });
    mockFetchKnowledge.mockResolvedValue({
      data: { status: 'ready', fileCount: 1, chunkCount: 1, graphStatus: { nodeCount: 0 } },
    });
  });

  const renderChat = () =>
    render(
      <MemoryRouter>
        <Chat />
      </MemoryRouter>,
    );

  describe('Basic Rendering', () => {
    it('renders the page title', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('智能问答')).toBeInTheDocument();
      });
    });

    it('renders the page description in retrieval mode when LLM is disabled', async () => {
      mockFetchLlmConfig.mockResolvedValue({ data: { apiKey: '' } });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText(/检索摘要模式/)).toBeInTheDocument();
      });
    });

    it('renders the page description in GraphRAG mode when LLM is enabled', async () => {
      mockFetchLlmConfig.mockResolvedValue({ data: { apiKey: 'sk-test' } });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText(/标准 GraphRAG/)).toBeInTheDocument();
      });
    });

    it('renders the send button', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /发送/ })).toBeInTheDocument();
      });
    });

    it('renders the input with placeholder', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.getByPlaceholderText(/例如：路由配置在哪里？/)).toBeInTheDocument();
      });
    });
  });

  describe('Sample Questions', () => {
    it('renders all sample questions when no messages and no repo', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('试试问这些问题')).toBeInTheDocument();
      });
      expect(screen.getByText('这个项目是做什么的？')).toBeInTheDocument();
      expect(screen.getByText('路由配置在哪里？')).toBeInTheDocument();
      expect(screen.getByText('如何启动项目？')).toBeInTheDocument();
    });

    it('fills the session input when a sample question is clicked and a repo is selected', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('路由配置在哪里？')).toBeInTheDocument();
      });
      fireEvent.click(screen.getByText('路由配置在哪里？'));
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      await waitFor(() => {
        expect(input.value).toBe('路由配置在哪里？');
      });
    });

    it('does not fill the input when no repo is selected', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('路由配置在哪里？')).toBeInTheDocument();
      });
      fireEvent.click(screen.getByText('路由配置在哪里？'));
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      // Click without currentRepoId is a no-op for store patching
      expect(input.value).toBe('');
    });
  });

  describe('Send Button State', () => {
    it('disables the send button when no repo is selected', async () => {
      renderChat();
      await waitFor(() => {
        const btn = screen.getByRole('button', { name: /发送/ });
        expect(btn).toBeDisabled();
      });
    });

    it('disables the send button when input is empty even if repo is selected', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      renderChat();
      await waitFor(() => {
        const btn = screen.getByRole('button', { name: /发送/ });
        expect(btn).toBeDisabled();
      });
    });

    it('enables the send button when both repo and input are present', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'hello' } });
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /发送/ })).not.toBeDisabled();
      });
    });

    it('treats whitespace-only input as empty and keeps button disabled', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: '   ' } });
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /发送/ })).toBeDisabled();
      });
    });
  });

  describe('Knowledge Base Alerts', () => {
    it('does not show a knowledge alert when no repo is selected', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.queryByText(/知识库已构建/)).not.toBeInTheDocument();
        expect(screen.queryByText(/尚未构建知识库/)).not.toBeInTheDocument();
      });
    });

    it('shows the success alert when knowledge is ready', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      mockFetchKnowledge.mockResolvedValue({
        data: { status: 'ready', fileCount: 3, chunkCount: 10, graphStatus: { nodeCount: 5 } },
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('知识库已构建')).toBeInTheDocument();
      });
    });

    it('shows the warning alert when knowledge is not ready', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      mockFetchKnowledge.mockResolvedValue({
        data: { status: 'not_indexed', fileCount: 0, chunkCount: 0, graphStatus: { nodeCount: 0 } },
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText(/尚未构建知识库/)).toBeInTheDocument();
      });
    });

    it('shows the warning alert when fetchKnowledge fails', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      mockFetchKnowledge.mockRejectedValue(new Error('boom'));
      renderChat();
      await waitFor(() => {
        expect(screen.getByText(/尚未构建知识库/)).toBeInTheDocument();
      });
    });
  });

  describe('Repo Selector', () => {
    it('renders the repo selector with options from context', async () => {
      mockUseRepoContext.mockReturnValue(
        buildRepoContext({
          repoList: [
            { id: 'r1', fullName: 'owner/repo1' },
            { id: 'r2', fullName: 'owner/repo2' },
          ],
        }),
      );
      renderChat();
      // antd Select placeholder is shown when no value
      await waitFor(() => {
        expect(screen.getByText('选择仓库')).toBeInTheDocument();
      });
    });
  });
});
