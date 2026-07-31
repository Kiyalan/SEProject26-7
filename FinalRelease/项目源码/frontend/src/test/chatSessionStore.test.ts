import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  abortChatRequest,
  beginChatRequest,
  clearChatSession,
  endChatRequest,
  getChatSession,
  isCurrentChatRequest,
  patchChatSession,
  subscribeChatSession,
  updateChatMessages,
} from '../lib/chatSessionStore';
import type { ChatMessage } from '../lib/FrontendTypes';

const REPO_A = 'repo-store-a';
const REPO_B = 'repo-store-b';
const NO_REPO = '__none__';

const makeMsg = (overrides: Partial<ChatMessage>): ChatMessage => ({
  id: `m-${Math.random().toString(36).slice(2)}`,
  role: 'user',
  content: 'hi',
  ...overrides,
});

describe('chatSessionStore', () => {
  beforeEach(() => {
    sessionStorage.clear();
    clearChatSession(REPO_A);
    clearChatSession(REPO_B);
    clearChatSession(NO_REPO);
  });

  afterEach(() => {
    clearChatSession(REPO_A);
    clearChatSession(REPO_B);
    clearChatSession(NO_REPO);
    sessionStorage.clear();
  });

  describe('getChatSession / ensure', () => {
    it('returns an empty snapshot for a brand-new repo', () => {
      const snap = getChatSession(REPO_A);
      expect(snap.messages).toEqual([]);
      expect(snap.loading).toBe(false);
      expect(snap.statusMessage).toBeNull();
      expect(snap.lastFailedQuestion).toBeNull();
      expect(snap.input).toBe('');
    });

    it('returns the __none__ sentinel with empty messages even when persisted data exists', () => {
      sessionStorage.setItem(
        `repopilot-chat:${NO_REPO}`,
        JSON.stringify([makeMsg({ content: 'stale' })]),
      );
      const snap = getChatSession(NO_REPO);
      expect(snap.messages).toEqual([]);
    });

    it('hydrates messages from sessionStorage on first access', () => {
      const persisted: ChatMessage[] = [
        makeMsg({ role: 'user', content: 'persisted' }),
      ];
      sessionStorage.setItem(`repopilot-chat:${REPO_A}`, JSON.stringify(persisted));
      const snap = getChatSession(REPO_A);
      expect(snap.messages).toHaveLength(1);
      expect(snap.messages[0].content).toBe('persisted');
      expect(snap.messages[0].streaming).toBe(false);
    });

    it('falls back to [] when persisted JSON is malformed', () => {
      sessionStorage.setItem(`repopilot-chat:${REPO_A}`, '{not json');
      const snap = getChatSession(REPO_A);
      expect(snap.messages).toEqual([]);
    });

    it('falls back to [] when persisted JSON is not an array', () => {
      sessionStorage.setItem(`repopilot-chat:${REPO_A}`, JSON.stringify({ foo: 'bar' }));
      const snap = getChatSession(REPO_A);
      expect(snap.messages).toEqual([]);
    });

    it('returns the same snapshot reference until the session is mutated', () => {
      const first = getChatSession(REPO_A);
      const second = getChatSession(REPO_A);
      expect(first).toBe(second);
      patchChatSession(REPO_A, { input: 'x' });
      const third = getChatSession(REPO_A);
      expect(third).not.toBe(first);
      expect(third.input).toBe('x');
    });
  });

  describe('subscribeChatSession', () => {
    it('invokes listeners on every mutation and supports unsubscribe', () => {
      const listener = vi.fn();
      const unsubscribe = subscribeChatSession(REPO_A, listener);
      patchChatSession(REPO_A, { input: 'one' });
      patchChatSession(REPO_A, { input: 'two' });
      unsubscribe();
      patchChatSession(REPO_A, { input: 'three' });
      expect(listener).toHaveBeenCalledTimes(2);
    });
  });

  describe('patchChatSession', () => {
    it('updates individual fields and skips persistence when messages are not in the patch', () => {
      patchChatSession(REPO_A, { input: 'hello' });
      const snap = getChatSession(REPO_A);
      expect(snap.input).toBe('hello');
      expect(sessionStorage.getItem(`repopilot-chat:${REPO_A}`)).toBeNull();
    });

    it('persists messages to sessionStorage when patched and clears streaming flags', () => {
      const user = makeMsg({ role: 'user', content: 'q' });
      const assistant = makeMsg({ role: 'assistant', content: 'a', streaming: true });
      patchChatSession(REPO_A, { messages: [user, assistant] });
      const raw = sessionStorage.getItem(`repopilot-chat:${REPO_A}`);
      expect(raw).not.toBeNull();
      const parsed = JSON.parse(raw!);
      expect(parsed).toHaveLength(2);
      expect(parsed.every((m: ChatMessage) => m.streaming === false)).toBe(true);
    });

    it('silently ignores persistence failures (quota / private mode)', () => {
      const setItemSpy = vi
        .spyOn(Storage.prototype, 'setItem')
        .mockImplementation(() => {
          throw new Error('QuotaExceeded');
        });
      expect(() =>
        patchChatSession(REPO_A, { messages: [makeMsg({ content: 'q' })] }),
      ).not.toThrow();
      setItemSpy.mockRestore();
    });

    it('updates loading, statusMessage, and lastFailedQuestion when patched', () => {
      patchChatSession(REPO_A, {
        loading: true,
        statusMessage: 'connecting',
        lastFailedQuestion: 'why?',
      });
      const snap = getChatSession(REPO_A);
      expect(snap.loading).toBe(true);
      expect(snap.statusMessage).toBe('connecting');
      expect(snap.lastFailedQuestion).toBe('why?');
    });
  });

  describe('updateChatMessages', () => {
    it('runs the updater, persists the result, and notifies listeners', () => {
      const listener = vi.fn();
      subscribeChatSession(REPO_A, listener);
      updateChatMessages(REPO_A, (prev) => [...prev, makeMsg({ content: 'new' })]);
      const snap = getChatSession(REPO_A);
      expect(snap.messages).toHaveLength(1);
      expect(snap.messages[0].content).toBe('new');
      const raw = sessionStorage.getItem(`repopilot-chat:${REPO_A}`);
      expect(JSON.parse(raw!)).toHaveLength(1);
      expect(listener).toHaveBeenCalled();
    });
  });

  describe('beginChatRequest / endChatRequest', () => {
    it('aborts a previous in-flight request, increments seq, and toggles loading', () => {
      const first = beginChatRequest(REPO_A);
      const second = beginChatRequest(REPO_A);
      expect(first.controller.signal.aborted).toBe(true);
      expect(second.seq).toBe(first.seq + 1);
      const mid = getChatSession(REPO_A);
      expect(mid.loading).toBe(true);
      expect(mid.statusMessage).toBe('正在连接…');
      expect(mid.lastFailedQuestion).toBeNull();
      endChatRequest(REPO_A, second.seq);
      const after = getChatSession(REPO_A);
      expect(after.loading).toBe(false);
      expect(after.statusMessage).toBeNull();
    });

    it('records the failed question when ending with a failed payload', () => {
      const { seq } = beginChatRequest(REPO_A);
      endChatRequest(REPO_A, seq, 'why?');
      expect(getChatSession(REPO_A).lastFailedQuestion).toBe('why?');
    });

    it('clears the failed question when ending successfully (no failedQuestion arg)', () => {
      const { seq } = beginChatRequest(REPO_A);
      endChatRequest(REPO_A, seq, 'why?');
      const { seq: next } = beginChatRequest(REPO_A);
      endChatRequest(REPO_A, next);
      expect(getChatSession(REPO_A).lastFailedQuestion).toBeNull();
    });

    it('does nothing when the stored seq no longer matches (stale end)', () => {
      const first = beginChatRequest(REPO_A);
      beginChatRequest(REPO_A);
      endChatRequest(REPO_A, first.seq, 'stale');
      const snap = getChatSession(REPO_A);
      expect(snap.lastFailedQuestion).toBeNull();
      expect(snap.loading).toBe(true);
    });

    it('isCurrentChatRequest returns true only for the active seq', () => {
      const first = beginChatRequest(REPO_A);
      beginChatRequest(REPO_A);
      expect(isCurrentChatRequest(REPO_A, first.seq)).toBe(false);
      const current = isCurrentChatRequest(REPO_A, getChatSession(REPO_A).requestSeq);
      expect(current).toBe(true);
    });

    it('persists messages without streaming flag when ending', () => {
      const user = makeMsg({ role: 'user', content: 'q' });
      const assistant = makeMsg({ role: 'assistant', content: 'a', streaming: true });
      updateChatMessages(REPO_A, () => [user, assistant]);
      const { seq } = beginChatRequest(REPO_A);
      endChatRequest(REPO_A, seq);
      const raw = JSON.parse(sessionStorage.getItem(`repopilot-chat:${REPO_A}`)!);
      expect(raw.every((m: ChatMessage) => m.streaming === false)).toBe(true);
    });
  });

  describe('abortChatRequest', () => {
    it('aborts the current controller and clears streaming flags', () => {
      const { controller } = beginChatRequest(REPO_A);
      updateChatMessages(REPO_A, () => [
        makeMsg({ role: 'assistant', content: 'x', streaming: true }),
      ]);
      abortChatRequest(REPO_A);
      expect(controller.signal.aborted).toBe(true);
      expect(getChatSession(REPO_A).messages[0].streaming).toBe(false);
    });

    it('is a no-op when there is no active request', () => {
      expect(() => abortChatSessionSafe(REPO_A)).not.toThrow();
    });
  });

  describe('clearChatSession', () => {
    it('aborts, empties messages/input, removes persisted data, and bumps seq', () => {
      const { seq: seq1 } = beginChatRequest(REPO_A);
      updateChatMessages(REPO_A, () => [makeMsg({ content: 'remember me' })]);
      patchChatSession(REPO_A, { input: 'kept input' });
      clearChatSession(REPO_A);
      const snap = getChatSession(REPO_A);
      expect(snap.messages).toEqual([]);
      expect(snap.input).toBe('');
      expect(snap.lastFailedQuestion).toBeNull();
      expect(snap.requestSeq).toBe(seq1 + 1);
      expect(sessionStorage.getItem(`repopilot-chat:${REPO_A}`)).toBeNull();
    });

    it('survives sessionStorage.removeItem throwing', () => {
      const removeSpy = vi
        .spyOn(Storage.prototype, 'removeItem')
        .mockImplementation(() => {
          throw new Error('blocked');
        });
      expect(() => clearChatSession(REPO_A)).not.toThrow();
      removeSpy.mockRestore();
    });
  });

  describe('isolation between repos', () => {
    it('keeps sessions isolated by repoId', () => {
      patchChatSession(REPO_A, { input: 'A' });
      patchChatSession(REPO_B, { input: 'B' });
      expect(getChatSession(REPO_A).input).toBe('A');
      expect(getChatSession(REPO_B).input).toBe('B');
    });
  });
});

// Helper used by the "no active request" test to avoid touching the real store
// shape and keep the assertion focused on the no-op behavior.
function abortChatSessionSafe(repoId: string) {
  abortChatRequest(repoId);
}
