import { onRequest } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import OpenAI from 'openai';

/*
 * SpeedyCAT AI answer-checker proxy (OpenAI). Desktop + mobile Anki forks call
 * this HTTP endpoint when no local OpenAI key is configured. The paid key lives
 * ONLY here (Firebase Secret Manager). Clients always have a deterministic
 * AI-off fallback, so any failure here just uses string-match + heuristic.
 */

const REGION = 'us-central1';
const DEFAULT_MODEL = 'gpt-5.4-mini';
const CHECKER_MODEL = process.env.SPEEDYCAT_CHECKER_MODEL?.trim() || DEFAULT_MODEL;
const MAX_OUTPUT_TOKENS = 600;
const REASONING_EFFORT = 'low';

const OPENAI_API_KEY = defineSecret('OPENAI_API_KEY');

export interface SpeedycatCheckRequest {
  front: string;
  typed: string;
  expected: string;
}

export interface SpeedycatCheckResponse {
  honest_attempt: boolean;
  verdict: 'correct' | 'incorrect';
  reason: string;
}

const SYSTEM_INSTRUCTION = [
  'You are SpeedyCAT\'s answer checker for a spaced-repetition flashcard app used to study for the MCAT.',
  'The learner is forced to type an answer from memory (active recall) before the back of the card is revealed.',
  'You are given the flashcard FRONT (the prompt), the learner\'s TYPED answer, and the EXPECTED answer (the back of the card).',
  'Decide two things and return them as JSON:',
  '1. honest_attempt: true if the typed answer is a GENUINE, good-faith attempt to recall THIS card\'s answer (even if wrong or partial).',
  '   Set it false only for clear non-attempts: blank/whitespace, random keyboard mashing, gibberish, a single unrelated character,',
  '   filler like \'idk\'/\'i don\'t know\'/\'dunno\', or text unrelated to the question that looks like gaming the reveal.',
  '2. verdict: \'correct\' if the typed answer matches the expected answer in MEANING (ignore case, spacing, punctuation, word order,',
  '   and reasonable synonyms/abbreviations, and minor misspellings/typos that preserve the intended meaning), otherwise \'incorrect\'. If honest_attempt is false, set verdict to \'incorrect\'.',
  'Also give a brief (max ~15 words) reason. Answer with ONLY the JSON object.',
].join('\n');

const JSON_SCHEMA: Record<string, unknown> = {
  type: 'object',
  additionalProperties: false,
  properties: {
    honest_attempt: {
      type: 'boolean',
      description:
        'true if the typed answer is a genuine good-faith recall attempt; false for blank/gibberish/keyboard-mash/unrelated/gaming input.',
    },
    verdict: {
      type: 'string',
      enum: ['correct', 'incorrect'],
      description:
        "'correct' if the typed answer matches the expected answer in meaning (case/format/synonym/typo insensitive), else 'incorrect'.",
    },
    reason: {
      type: 'string',
      description: 'Brief explanation of the judgement (max ~15 words).',
    },
  },
  required: ['honest_attempt', 'verdict', 'reason'],
};

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

export function buildCheckPrompt(front: string, typed: string, expected: string): string {
  return [
    `FRONT (the prompt shown to the learner): ${front.trim() || '(empty)'}`,
    `TYPED answer (what the learner recalled): ${typed.trim() || '(blank)'}`,
    `EXPECTED answer (the back of the card): ${expected.trim() || '(empty)'}`,
    '',
    'Return the JSON verdict now.',
  ].join('\n');
}

/** Validates `request.data` into a {@link SpeedycatCheckRequest}. */
export function parseCheckInput(data: unknown): SpeedycatCheckRequest {
  if (!data || typeof data !== 'object') {
    throw new Error('Expected a JSON object.');
  }
  const raw = data as Record<string, unknown>;
  return {
    front: asString(raw.front),
    typed: asString(raw.typed),
    expected: asString(raw.expected),
  };
}

/** Exported for unit tests. */
export function parseCheckResponse(rawText: string): SpeedycatCheckResponse | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawText);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') {
    return null;
  }
  const candidate = parsed as Record<string, unknown>;
  const honest = candidate.honest_attempt;
  let verdict = candidate.verdict;
  if (typeof honest !== 'boolean') {
    return null;
  }
  if (verdict !== 'correct' && verdict !== 'incorrect') {
    return null;
  }
  const finalVerdict: 'correct' | 'incorrect' = honest ? verdict : 'incorrect';
  const reason = typeof candidate.reason === 'string' ? candidate.reason.trim() : '';
  return { honest_attempt: honest, verdict: finalVerdict, reason };
}

let cachedClient: OpenAI | null = null;
function getOpenAiClient(): OpenAI {
  if (!cachedClient) {
    cachedClient = new OpenAI({ apiKey: OPENAI_API_KEY.value() });
  }
  return cachedClient;
}

/**
 * HTTP proxy for SpeedyCAT's flashcard answer checker. POST JSON
 * `{ front, typed, expected }` → `{ honest_attempt, verdict, reason }`.
 * No auth (clients fall back to deterministic checking on any error).
 */
export const checkSpeedycatAnswer = onRequest(
  {
    region: REGION,
    secrets: [OPENAI_API_KEY],
    maxInstances: 10,
    timeoutSeconds: 30,
    cors: true,
  },
  async (req, res) => {
    if (req.method === 'OPTIONS') {
      res.status(204).send('');
      return;
    }
    if (req.method !== 'POST') {
      res.status(405).json({ error: 'Method not allowed. Use POST.' });
      return;
    }

    let input: SpeedycatCheckRequest;
    try {
      input = parseCheckInput(req.body);
    } catch {
      res.status(400).json({ error: 'Invalid request body.' });
      return;
    }

    try {
      const client = getOpenAiClient();
      const response = await client.responses.create({
        model: CHECKER_MODEL,
        instructions: SYSTEM_INSTRUCTION,
        input: buildCheckPrompt(input.front, input.typed, input.expected),
        max_output_tokens: MAX_OUTPUT_TOKENS,
        reasoning: { effort: REASONING_EFFORT },
        text: {
          format: {
            type: 'json_schema',
            name: 'speedycat_answer_check',
            schema: JSON_SCHEMA,
            strict: true,
          },
        },
      });

      const parsed = parseCheckResponse(response.output_text ?? '');
      if (!parsed) {
        res.status(502).json({ error: 'AI returned an empty or unparseable response.' });
        return;
      }
      res.status(200).json(parsed);
    } catch {
      res.status(503).json({ error: 'OpenAI request failed.' });
    }
  },
);
