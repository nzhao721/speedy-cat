import { describe, expect, it } from 'vitest';
import {
  buildExplanationPrompt,
  parseExplanationInput,
  parseExplanationResponse,
} from './speedycatExplanationChecker';

describe('speedycatExplanationChecker', () => {
  it('buildExplanationPrompt includes stem and internal answer, not choices', () => {
    const prompt = buildExplanationPrompt(
      'Which base pairs with adenine in DNA?',
      'Thymine pairs with adenine via two hydrogen bonds.',
      'Thymine',
    );
    expect(prompt).toContain('Which base pairs with adenine');
    expect(prompt).toContain('Thymine pairs with adenine');
    expect(prompt).toContain('Thymine');
    expect(prompt).not.toContain('cytosine');
    expect(prompt).not.toContain('Answer choices');
  });

  it('parseExplanationInput accepts a valid body', () => {
    expect(
      parseExplanationInput({
        stem: 's',
        userExplanation: 'u',
        correctAnswer: 'c',
      }),
    ).toEqual({ stem: 's', userExplanation: 'u', correctAnswer: 'c' });
  });

  it('parseExplanationResponse accepts valid JSON', () => {
    const result = parseExplanationResponse(
      JSON.stringify({ pass: true, feedback: 'good reasoning' }),
    );
    expect(result).toEqual({ pass: true, feedback: 'good reasoning' });
  });

  it('parseExplanationResponse returns null for malformed JSON', () => {
    expect(parseExplanationResponse('not json')).toBeNull();
    expect(parseExplanationResponse(JSON.stringify({ feedback: 'x' }))).toBeNull();
  });
});
