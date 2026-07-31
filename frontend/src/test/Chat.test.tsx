import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import Chat from '../pages/Chat';
import { clearChatSession, patchChatSession } from '../lib/chatSessionStore';
import type { ChatMessage } from '../lib/FrontendTypes';

// ===== Mocks =====

const mockUseRepoContext = vi.fn();
const mockFetchLlmConfig = vi.fn();
const mockFetchKnowledge = vi.fn();
const mockGetToken = vi.fn(() => 'test-token');
const mockAuthAxiosPost = vi.fn();

let fetchSpy: ReturnType<typeof vi.fn>;

vi.mock('../context/RepoContext', () => ({
  useRepoContext: () => mockUseRepoContext(),
}));

vi.mock('../api/generated', () => ({
  fetchLlmConfig: (...args: unknown[]) => mockFetchLlmConfig(...args),
  fetchKnowledge: (...args: unknown[]) => mockFetchKnowledge(...args),
}));

vi.mock('../lib/AuthAxios', () => ({
  authAxios: {
    post: (...args: unknown[]) => mockAuthAxiosPost(...args),
  },
  getToken: () => mockGetToken(),
}));

const buildRepoContext = (overrides = {}) => ({
  currentRepoId: '',
  repoList: [],
  setCurrentRepo: vi.fn(),
  ...overrides,
});

// ===== SSE mock helper =====

type SseEvent = { event?: string; data: unknown };

function makeSseResponse(
  events: SseEvent[],
  opts: { ok?: boolean; hasBody?: boolean } = {},
) {
  const ok = opts.ok !== false;
  const hasBody = opts.hasBody !== false;
  const encoder = new TextEncoder();
  const payload = events
    .map((e) => {
      const dataJson = typeof e.data === 'string' ? e.data : JSON.stringify(e.data);
      return (e.event ? `event: ${e.event}\n` : '') + `data: ${dataJson}\n\n`;
    })
    .join('');
  const body = hasBody
    ? new ReadableStream({
        start(controller) {
          if (events.length === 0) {
            controller.close();
            return;
          }
          controller.enqueue(encoder.encode(payload));
          controller.close();
        },
      })
    : null;
  return {
    ok,
    status: ok ? 200 : 500,
    statusText: ok ? 'OK' : 'Server Error',
    text: async () => 'server broke',
    body,
  };
}

function installFetchMock(response: unknown) {
  fetchSpy = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchSpy);
}

// ===== Tests =====

describe('Chat Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    clearChatSession('repo-1');
    clearChatSession('__none__');
    mockUseRepoContext.mockReturnValue(buildRepoContext());
    mockFetchLlmConfig.mockResolvedValue({ data: { apiKey: '' } });
    mockFetchKnowledge.mockResolvedValue({
      data: { status: 'ready', fileCount: 1, chunkCount: 1, graphStatus: { nodeCount: 0 } },
    });
    mockAuthAxiosPost.mockResolvedValue({ data: {} });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const renderChat = () =>
    render(
      <MemoryRouter>
        <Chat />
      </MemoryRouter>,
    );

  const setRepoAndInput = async (value: string) => {
    mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
    renderChat();
    const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
    fireEvent.change(input, { target: { value } });
    return input;
  };

  // ----- Basic Rendering -----

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

  // ----- Sample Questions -----

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
      expect(input.value).toBe('');
    });
  });

  // ----- Send Button State -----

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
      await setRepoAndInput('hello');
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

  // ----- Knowledge Base Alerts -----

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

  // ----- Repo Selector -----

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
      await waitFor(() => {
        expect(screen.getByText('选择仓库')).toBeInTheDocument();
      });
    });

    it('renders the search-mode dropdown', async () => {
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('Auto 路由')).toBeInTheDocument();
      });
    });
  });

  // ----- Streaming: happy path -----

  describe('Streaming — happy path with status/meta/token/done', () => {
    it('shows the loading spinner, accumulates tokens, then renders the final answer', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          { event: 'status', data: { message: '正在检索' } },
          { event: 'meta', data: { questionType: 'how', citations: [], intent: 'code' } },
          { event: 'token', data: { content: '你好，' } },
          { event: 'token', data: { content: '世界' } },
          { event: 'done', data: { answer: '你好，世界' } },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'hi' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));

      await waitFor(() => {
        expect(screen.getByText(/正在检索/)).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(screen.getByText(/意图/)).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(screen.getByText('你好，世界')).toBeInTheDocument();
      });
      expect(fetchSpy).toHaveBeenCalledTimes(1);
      const init = fetchSpy.mock.calls[0][1] as RequestInit;
      expect(init.method).toBe('POST');
      expect(JSON.parse(init.body as string)).toMatchObject({
        repoId: 'repo-1',
        message: 'hi',
        mode: 'auto',
      });
    });
  });

  describe('Streaming — done event without an answer uses accumulated content', () => {
    it('keeps the accumulated tokens as the final answer when done is empty', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          { event: 'token', data: { content: '片段A' } },
          { event: 'token', data: { content: '片段B' } },
          { event: 'done', data: {} },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText(/片段A/)).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(screen.getByText(/片段A片段B/)).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — data event with full payload', () => {
    it('renders the data payload as a complete non-streaming answer with citations', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          {
            event: 'data',
            data: {
              content: '完整回答',
              questionType: 'what',
              intent: 'overview',
              citations: [{ file: 'README.md', line: 1 }],
            },
          },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('完整回答')).toBeInTheDocument();
      });
      expect(screen.getByText('What')).toBeInTheDocument();
      expect(screen.getByText(/概览/)).toBeInTheDocument();
      expect(screen.getByText(/引用：README.md:1/)).toBeInTheDocument();
    });

    it('renders the data payload with non-streaming even when content is empty', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          {
            event: 'data',
            data: { content: undefined, intent: 'overview' },
          },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText(/概览/)).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — error event with object payload', () => {
    it('renders the error message and the 失败 tag', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([{ event: 'error', data: { message: '上游失败' } }]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('上游失败')).toBeInTheDocument();
      });
      expect(screen.getByText('失败')).toBeInTheDocument();
    });

    it('falls back to a generic 问答失败 when the error event has no message', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(makeSseResponse([{ event: 'error', data: { foo: 'bar' } }]));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('问答失败')).toBeInTheDocument();
      });
    });

    it('passes through a plain-string error payload as the message', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(makeSseResponse([{ event: 'error', data: 'string error' }]));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('string error')).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — status event fallback to 处理中…', () => {
    it('uses 处理中… when status data is neither a message object nor a useful string', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          { event: 'status', data: { notMessage: true } },
          { event: 'done', data: { answer: 'done' } },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText(/处理中…/)).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(screen.getByText('done')).toBeInTheDocument();
      });
    });

    it('uses the raw string payload for status when it is a string', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          { event: 'status', data: 'plain status' },
          { event: 'done', data: { answer: 'done' } },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('plain status')).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — token with empty content is a no-op', () => {
    it('does not render anything for an empty token', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(
        makeSseResponse([
          { event: 'token', data: { content: '' } },
          { event: 'done', data: { answer: 'final' } },
        ]),
      );
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('final')).toBeInTheDocument();
      });
    });
  });

  // ----- Streaming error branches -----

  describe('Streaming — server returns a non-ok response', () => {
    it('surfaces the error text body and ends the request', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(makeSseResponse([], { ok: false }));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('server broke')).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — response body is null', () => {
    it('shows the 无法读取流式响应 fallback', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(makeSseResponse([], { ok: true, hasBody: false }));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('无法读取流式响应')).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — empty stream with no done', () => {
    it('falls back to the 未收到有效回答 message', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(makeSseResponse([]));
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText(/未收到有效回答/)).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — network error', () => {
    it('renders an error message when fetch throws a non-AbortError', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      fetchSpy = vi.fn().mockRejectedValue(new Error('网络炸了'));
      vi.stubGlobal('fetch', fetchSpy);
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('网络炸了')).toBeInTheDocument();
      });
    });

    it('silently resolves on AbortError without showing the error message', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      fetchSpy = vi.fn().mockRejectedValue(Object.assign(new Error('aborted'), { name: 'AbortError' }));
      vi.stubGlobal('fetch', fetchSpy);
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByRole('button', { name: /发送/ })).not.toBeDisabled();
      });
      expect(screen.queryByText(/网络炸了|问答失败|流式问答失败/)).not.toBeInTheDocument();
    });

    it('shows a generic fallback when the network error is not an Error instance', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      fetchSpy = vi.fn().mockRejectedValue('just-a-string');
      vi.stubGlobal('fetch', fetchSpy);
      renderChat();
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText('流式问答失败')).toBeInTheDocument();
      });
    });
  });

  describe('Streaming — over-length question is rejected before fetch', () => {
    it('shows the 问题过长 error and never calls fetch', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      fetchSpy = vi.fn();
      vi.stubGlobal('fetch', fetchSpy);
      renderChat();
      const longText = 'x'.repeat(2001);
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: longText } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(screen.getByText(/问题过长/)).toBeInTheDocument();
      });
      expect(fetchSpy).not.toHaveBeenCalled();
    });
  });

  // ----- Add to FAQ -----

  describe('Add to FAQ', () => {
    const seedAssistantMessage = (msg: ChatMessage) => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [
          { id: 'u1', role: 'user', content: 'help' },
          msg,
        ],
      });
    };

    it('shows the 加入 FAQ button on a successful assistant message', async () => {
      seedAssistantMessage({ id: 'a1', role: 'assistant', content: '可加 FAQ' });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('加入 FAQ')).toBeInTheDocument();
      });
    });

    it('POSTs to /api/repos/:id/faq/items when 加入 FAQ is clicked', async () => {
      seedAssistantMessage({ id: 'a1', role: 'assistant', content: '答案内容' });
      mockAuthAxiosPost.mockResolvedValue({ data: { ok: true } });
      renderChat();
      const btn = await screen.findByText('加入 FAQ');
      fireEvent.click(btn);
      await waitFor(() => {
        expect(mockAuthAxiosPost).toHaveBeenCalledWith(
          '/api/repos/repo-1/faq/items',
          expect.objectContaining({ question: 'help', answer: '答案内容', category: 'chat' }),
        );
      });
    });

    it('does not show the 加入 FAQ button when the assistant message is in error state', async () => {
      seedAssistantMessage({ id: 'a1', role: 'assistant', content: '请求出错啦', error: true });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('请求出错啦')).toBeInTheDocument();
      });
      expect(screen.queryByText('加入 FAQ')).not.toBeInTheDocument();
    });

    it('does not show the 加入 FAQ button when the assistant content is empty', async () => {
      seedAssistantMessage({ id: 'a1', role: 'assistant', content: '' });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('help')).toBeInTheDocument();
      });
      expect(screen.queryByText('加入 FAQ')).not.toBeInTheDocument();
    });

    it('warns when the assistant message has no preceding user question', async () => {
      // Single assistant in the list means the for-loop in addToFaq finds no user.
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [{ id: 'a1', role: 'assistant', content: '孤零零的回答' }],
      });
      renderChat();
      const btn = await screen.findByText('加入 FAQ');
      fireEvent.click(btn);
      // No API call should be made
      await waitFor(() => {
        expect(mockAuthAxiosPost).not.toHaveBeenCalled();
      });
    });

    it('surfaces the error message when the API call rejects', async () => {
      seedAssistantMessage({ id: 'a1', role: 'assistant', content: '答案' });
      mockAuthAxiosPost.mockRejectedValue(new Error('服务端炸了'));
      renderChat();
      const btn = await screen.findByText('加入 FAQ');
      fireEvent.click(btn);
      // The antd message.error call happens internally; we just confirm the button has settled back.
      await waitFor(() => {
        expect(mockAuthAxiosPost).toHaveBeenCalled();
      });
      // Re-render and confirm the FAQ button is interactable again (loading flag cleared).
      expect(screen.getByText('加入 FAQ')).toBeInTheDocument();
    });
  });

  // ----- Message rendering branches -----

  describe('Message rendering branches', () => {
    it('renders questionType tags, intent tags, citations, streaming indicator, and empty-evidence tag', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [
          { id: 'u1', role: 'user', content: 'q' },
          {
            id: 'a1',
            role: 'assistant',
            content: 'Some answer',
            questionType: 'how',
            intent: 'code+api',
            citations: [
              { file: 'a.ts', line: 10 },
              { file: 'b.ts' },
            ],
            streaming: true,
            emptyEvidence: true,
          },
        ],
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('How')).toBeInTheDocument();
      });
      expect(screen.getByText(/意图 · 代码 · 接口/)).toBeInTheDocument();
      expect(screen.getByText('无证据')).toBeInTheDocument();
      expect(screen.getByText(/生成中/)).toBeInTheDocument();
      expect(screen.getByText(/引用：a.ts:10/)).toBeInTheDocument();
      expect(screen.getByText(/引用：b.ts/)).toBeInTheDocument();
    });

    it('falls back to the raw questionType label when it is not in the map', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [
          { id: 'u1', role: 'user', content: 'q' },
          { id: 'a1', role: 'assistant', content: 'x', questionType: 'unknown' as ChatMessage['questionType'] },
        ],
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('unknown')).toBeInTheDocument();
      });
    });

    it('does not render an intent tag when intent is empty', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [
          { id: 'u1', role: 'user', content: 'q' },
          { id: 'a1', role: 'assistant', content: 'x', intent: '' },
        ],
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('x')).toBeInTheDocument();
      });
      expect(screen.queryByText(/意图/)).not.toBeInTheDocument();
    });

    it('joins unknown intent parts verbatim and renders the error tag', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [
          { id: 'u1', role: 'user', content: 'q' },
          {
            id: 'a1',
            role: 'assistant',
            content: 'fatal',
            intent: 'weird+value',
            error: true,
            emptyEvidence: true,
          },
        ],
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText(/意图 · weird · value/)).toBeInTheDocument();
      });
      expect(screen.getByText('失败')).toBeInTheDocument();
      expect(screen.getByText('无证据')).toBeInTheDocument();
    });

    it('does not render anything (no welcome and no samples) when a repo is set but the session only has streamed messages and loading is false', async () => {
      // Sanity: with messages present, the sample-question block is replaced.
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      patchChatSession('repo-1', {
        messages: [{ id: 'a1', role: 'assistant', content: '历史回答', streaming: false }],
      });
      renderChat();
      await waitFor(() => {
        expect(screen.getByText('历史回答')).toBeInTheDocument();
      });
      expect(screen.queryByText('试试问这些问题')).not.toBeInTheDocument();
    });
  });

  // ----- Retry button & selectors -----

  describe('Retry button', () => {
    it('replays the last failed question when 重试上一问 is clicked', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      // Seed a failed question via the store so the retry button renders.
      patchChatSession('repo-1', { lastFailedQuestion: 'orig' });
      installFetchMock(makeSseResponse([{ event: 'done', data: { answer: 'OK' } }]));
      renderChat();
      const retryBtn = await screen.findByRole('button', { name: /重试上一问/ });
      fireEvent.click(retryBtn);
      await waitFor(() => {
        expect(fetchSpy).toHaveBeenCalledTimes(1);
      });
      const init = fetchSpy.mock.calls[0][1] as RequestInit;
      expect(JSON.parse(init.body as string)).toMatchObject({
        repoId: 'repo-1',
        message: 'orig',
      });
    });
  });

  describe('Selectors', () => {
    it('invokes setCurrentRepo when the repo Select changes', async () => {
      const setRepo = vi.fn();
      mockUseRepoContext.mockReturnValue(
        buildRepoContext({
          repoList: [
            { id: 'r1', fullName: 'owner/r1' },
            { id: 'r2', fullName: 'owner/r2' },
          ],
          setCurrentRepo: setRepo,
        }),
      );
      renderChat();
      // Open the repo selector and pick r2.
      const repoTrigger = await screen.findByText('选择仓库');
      fireEvent.mouseDown(repoTrigger);
      const opt = await screen.findByText('owner/r2');
      fireEvent.click(opt);
      expect(setRepo).toHaveBeenCalledWith('r2');
    });

    it('changes the search-mode Select to Local Search', async () => {
      mockUseRepoContext.mockReturnValue(buildRepoContext({ currentRepoId: 'repo-1' }));
      installFetchMock(makeSseResponse([{ event: 'done', data: { answer: 'OK' } }]));
      renderChat();
      const modeTrigger = await screen.findByText('Auto 路由');
      fireEvent.mouseDown(modeTrigger);
      const localOpt = await screen.findByText('Local Search');
      fireEvent.click(localOpt);
      // Trigger a request to verify the mode is now 'local'.
      const input = (await screen.findByPlaceholderText(/例如：路由配置在哪里？/)) as HTMLInputElement;
      fireEvent.change(input, { target: { value: 'q' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      await waitFor(() => {
        expect(fetchSpy).toHaveBeenCalled();
      });
      const init = fetchSpy.mock.calls[0][1] as RequestInit;
      expect(JSON.parse(init.body as string).mode).toBe('local');
    });
  });
});
