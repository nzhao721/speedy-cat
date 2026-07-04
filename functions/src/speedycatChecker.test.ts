import { describe, expect, it } from 'vitest';
import {
  buildCheckPrompt,
  parseCheckInput,
  parseCheckResponse,
} from './speedycatChecker';

describe('speedycatChecker', () => {
  it('buildCheckPrompt includes front, typed, and expected', () => {
    const prompt = buildCheckPrompt('What is ATP?', 'adenosine triphosphate', 'ATP');
    expect(prompt).toContain('What is ATP?');
    expect(prompt).toContain('adenosine triphosphate');
    expect(prompt).toContain('ATP');
  });

  it('parseCheckInput accepts a valid body', () => {
    expect(parseCheckInput({ front: 'f', typed: 't', expected: 'e' })).toEqual({
      front: 'f',
      typed: 't',
      expected: 'e',
    });
  });

  it('parseCheckInput rejects non-objects', () => {
    expect(() => parseCheckInput(null)).toThrow();
  });

  it('parseCheckResponse accepts valid JSON', () => {
    const result = parseCheckResponse(
      JSON.stringify({ honest_attempt: true, verdict: 'correct', reason: 'matches' }),
    );
    expect(result).toEqual({ honest_attempt: true, verdict: 'correct', reason: 'matches' });
  });

  it('parseCheckResponse forces dishonest to incorrect', () => {
    const result = parseCheckResponse(
      JSON.stringify({ honest_attempt: false, verdict: 'correct', reason: 'mash' }),
    );
    expect(result?.verdict).toBe('incorrect');
  });

  it('parseCheckResponse returns null for malformed JSON', () => {
    expect(parseCheckResponse('not json')).toBeNull();
    expect(parseCheckResponse(JSON.stringify({ verdict: 'correct' }))).toBeNull();
  });
});
