import { describe, it, expect } from 'vitest';

// Test the formatIntent function logic directly
describe('formatIntent Function', () => {
  const intentLabels: Record<string, string> = {
    code: '代码',
    history: '历史',
    api: '接口',
    deployment: '部署',
    overview: '概览',
  };

  function formatIntent(intent?: string) {
    if (!intent) return null;
    return intent
      .split('+')
      .filter(Boolean)
      .map((part) => intentLabels[part] || part)
      .join(' · ');
  }

  it('should return null for undefined intent', () => {
    expect(formatIntent(undefined)).toBeNull();
  });

  it('should return null for empty intent', () => {
    expect(formatIntent('')).toBeNull();
  });

  it('should format single intent', () => {
    expect(formatIntent('code')).toBe('代码');
  });

  it('should format multiple intents joined with dot', () => {
    expect(formatIntent('code+api')).toBe('代码 · 接口');
  });

  it('should format three intents', () => {
    expect(formatIntent('code+api+deployment')).toBe('代码 · 接口 · 部署');
  });

  it('should handle unknown intent labels as-is', () => {
    expect(formatIntent('custom')).toBe('custom');
  });

  it('should handle mixed known and unknown intents', () => {
    expect(formatIntent('code+unknown')).toBe('代码 · unknown');
  });

  it('should filter empty parts', () => {
    expect(formatIntent('code++api')).toBe('代码 · 接口');
  });

  it('should handle all known intents', () => {
    expect(formatIntent('code+history+api+deployment+overview')).toBe(
      '代码 · 历史 · 接口 · 部署 · 概览'
    );
  });
});
