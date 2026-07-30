import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import Chat from '../pages/Chat';

describe('Chat Page (Simplified)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const renderChat = () =>
    render(
      <MemoryRouter>
        <Chat />
      </MemoryRouter>,
    );

  describe('Basic Rendering', () => {
    it('should render page title', () => {
      renderChat();
      expect(screen.getByText('智能问答')).toBeInTheDocument();
    });

    it('should render page description', () => {
      renderChat();
      expect(screen.getByText(/检索摘要模式/)).toBeInTheDocument();
    });

    it('should render send button', () => {
      renderChat();
      expect(screen.getByRole('button', { name: /发送/ })).toBeInTheDocument();
    });

    it('should render input area with placeholder', () => {
      renderChat();
      expect(
        screen.getByPlaceholderText(/例如：路由配置在哪里？/),
      ).toBeInTheDocument();
    });

    it('should render sample questions when no messages', () => {
      renderChat();
      expect(screen.getByText('试试问这些问题')).toBeInTheDocument();
      expect(screen.getByText('这个项目是做什么的？')).toBeInTheDocument();
      expect(screen.getByText('路由配置在哪里？')).toBeInTheDocument();
      expect(screen.getByText('如何启动项目？')).toBeInTheDocument();
    });

    it('should not render sample questions after sending', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: 'test question' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      expect(screen.queryByText('试试问这些问题')).not.toBeInTheDocument();
    });
  });

  describe('Sample Question Interaction', () => {
    it('should fill input when clicking a sample question', () => {
      renderChat();
      fireEvent.click(screen.getByText('路由配置在哪里？'));
      const input = screen.getByPlaceholderText(
        /例如：路由配置在哪里？/,
      ) as HTMLTextAreaElement;
      expect(input.value).toBe('路由配置在哪里？');
    });

    it('should fill input with different sample questions independently', () => {
      renderChat();
      fireEvent.click(screen.getByText('如何启动项目？'));
      const input = screen.getByPlaceholderText(
        /例如：路由配置在哪里？/,
      ) as HTMLTextAreaElement;
      expect(input.value).toBe('如何启动项目？');
    });
  });

  describe('Sending Messages', () => {
    it('should add user message when sending', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: 'Hello world' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));

      expect(screen.getByText('Hello world')).toBeInTheDocument();
    });

    it('should add assistant response when sending', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: '测试' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));

      expect(screen.getByText('已收到问题：测试')).toBeInTheDocument();
    });

    it('should clear input after sending', () => {
      renderChat();
      const input = screen.getByPlaceholderText(
        /例如：路由配置在哪里？/,
      ) as HTMLTextAreaElement;
      fireEvent.change(input, { target: { value: 'test' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      expect(input.value).toBe('');
    });

    it('should not send empty input', () => {
      renderChat();
      const sendButton = screen.getByRole('button', { name: /发送/ });
      expect(sendButton).toBeDisabled();
      fireEvent.click(sendButton);
      expect(screen.queryByText('已收到问题：')).not.toBeInTheDocument();
    });

    it('should not send whitespace-only input', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: '   ' } });
      const sendButton = screen.getByRole('button', { name: /发送/ });
      expect(sendButton).toBeDisabled();
    });

    it('should enable send button when input has content', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: 'hello' } });
      const sendButton = screen.getByRole('button', { name: /发送/ });
      expect(sendButton).not.toBeDisabled();
    });

    it('should send via Enter key', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: 'enter question' } });
      fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
      expect(screen.getByText('enter question')).toBeInTheDocument();
    });

    it('should allow newline with Shift+Enter without sending', () => {
      renderChat();
      const input = screen.getByPlaceholderText(
        /例如：路由配置在哪里？/,
      ) as HTMLTextAreaElement;
      fireEvent.change(input, { target: { value: 'multi' } });
      fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', shiftKey: true });
      // Shift+Enter 不应触发发送，消息列表应仍为空
      expect(screen.queryByText('已收到问题：multi')).not.toBeInTheDocument();
      // 输入内容应保留
      expect(input.value).toBe('multi');
    });

    it('should accumulate multiple messages', async () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);

      fireEvent.change(input, { target: { value: 'first' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));

      fireEvent.change(input, { target: { value: 'second' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));

      await waitFor(() => {
        expect(screen.getByText('first')).toBeInTheDocument();
        expect(screen.getByText('second')).toBeInTheDocument();
        expect(screen.getByText('已收到问题：first')).toBeInTheDocument();
        expect(screen.getByText('已收到问题：second')).toBeInTheDocument();
      });
    });
  });

  describe('Knowledge Base Alert', () => {
    it('should not show knowledge warning when hasKnowledge is true', () => {
      renderChat();
      expect(
        screen.queryByText(/当前仓库尚未构建知识库/),
      ).not.toBeInTheDocument();
    });
  });

  describe('Special Characters', () => {
    it('should handle emoji input', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: '你好 🚀' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      expect(screen.getByText('你好 🚀')).toBeInTheDocument();
    });

    it('should handle HTML/script-like content as text', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, {
        target: { value: '<script>alert("xss")</script>' },
      });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      expect(
        screen.getByText('<script>alert("xss")</script>'),
      ).toBeInTheDocument();
    });

    it('should handle SQL-like content as text', () => {
      renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: "'; DROP TABLE users; --" } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));
      expect(
        screen.getByText("'; DROP TABLE users; --"),
      ).toBeInTheDocument();
    });
  });

  describe('Message Styling', () => {
    it('should render user and assistant messages with different backgrounds', () => {
      const { container } = renderChat();
      const input = screen.getByPlaceholderText(/例如：路由配置在哪里？/);
      fireEvent.change(input, { target: { value: 'style test' } });
      fireEvent.click(screen.getByRole('button', { name: /发送/ }));

      const allDivs = Array.from(
        container.querySelectorAll('div'),
      ) as HTMLElement[];
      const userBubble = allDivs.find((d) => {
        const ct = d.style.cssText.toLowerCase();
        return ct.includes('rgb(22, 93, 255)') || ct.includes('#165dff');
      });
      const assistantBubble = allDivs.find((d) => {
        const ct = d.style.cssText.toLowerCase();
        return ct.includes('rgb(245, 247, 250)') || ct.includes('#f5f7fa');
      });

      expect(userBubble).toBeTruthy();
      expect(assistantBubble).toBeTruthy();
      expect(userBubble!.style.cssText.toLowerCase()).toMatch(
        /rgb\(22, ?93, ?255\)|#165dff/,
      );
      expect(assistantBubble!.style.cssText.toLowerCase()).toMatch(
        /rgb\(245, ?247, ?250\)|#f5f7fa/,
      );
    });
  });
});