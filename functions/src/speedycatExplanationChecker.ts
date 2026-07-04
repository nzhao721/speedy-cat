import { onRequest } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import OpenAI from 'openai';

/*
 * SpeedyCAT practice explanation verifier proxy. Desktop + mobile call this when
 * a learner must explain a correct MCQ answer. The correct answer is sent for
 * internal judging only; feedback must never reveal it.
 */

const REGION = 'us-central1';
const DEFAULT_MODEL = 'gpt-5.4-mini';
const CHECKER_MODEL = process.env.SPEEDYCAT_EXPLANATION_MODEL?.trim() || DEFAULT_MODEL;
const MAX_OUTPUT_TOKENS = 600;
const REASONING_EFFORT = 'low';

const OPENAI_API_KEY = defineSecret('OPENAI_API_KEY');

export interface ExplanationCheckRequest {
  stem: string;
  userExplanation: string;
  correctAnswer: string;
}

export interface ExplanationCheckResponse {
  pass: boolean;
  feedback: string;
}

const SYSTEM_INSTRUCTION = [
  'You are SpeedyCAT\'s practice-question explanation evaluator for MCAT study.',
  'The learner answered a multiple-choice question correctly and must explain WHY.',
  'You receive:',
  '- QUESTION STEM (no answer choices shown to the learner)',
  '- LEARNER\'S WRITTEN EXPLANATION',
  '- CORRECT ANSWER (internal reference ONLY — use it to judge correctness; NEVER quote it, name its letter, or paraphrase it closely in feedback)',
  '',
  'Judge whether the explanation:',
  '1. Is substantive: at least 2–3 sentences with genuine reasoning (not filler, gaming, or a single vague phrase).',
  '2. Demonstrates understanding of WHY the correct answer is correct for this specific question.',
  '',
  'Return JSON with:',
  '- pass: true only when BOTH criteria are met.',
  '- feedback: brief coaching for the learner (max ~30 words). When pass is false, nudge them toward deeper reasoning WITHOUT revealing or closely restating the correct answer.',
  'Answer with ONLY the JSON object.',
].join('\n');

const JSON_SCHEMA: Record<string, unknown> = {
  type: 'object',
  additionalProperties: false,
  properties: {
    pass: {
      type: 'boolean',
      description:
        'true when the explanation is substantive and shows why the correct answer is correct.',
    },
    feedback: {
      type: 'string',
      description: 'Brief coaching message; must not reveal the correct answer.',
    },
  },
  required: ['pass', 'feedback'],
};

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/** Evaluator prompt — stem + learner text + internal correct answer (no choices). */
export function buildExplanationPrompt(
  stem: string,
  userExplanation: string,
  correctAnswer: string,
): string {
  return [
    `QUESTION STEM: ${stem.trim() || '(empty)'}`,
    `LEARNER'S WRITTEN EXPLANATION: ${userExplanation.trim() || '(blank)'}`,
    `CORRECT ANSWER (internal reference — judge only; do not reveal): ${correctAnswer.trim() || '(empty)'}`,
    '',
    'Return the JSON verdict now.',
  ].join('\n');
}

export function parseExplanationInput(data: unknown): ExplanationCheckRequest {
  if (!data || typeof data !== 'object') {
    throw new Error('Expected a JSON object.');
  }
  const raw = data as Record<string, unknown>;
  return {
    stem: asString(raw.stem),
    userExplanation: asString(raw.userExplanation),
    correctAnswer: asString(raw.correctAnswer),
  };
}

export function parseExplanationResponse(rawText: string): ExplanationCheckResponse | null {
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
  const pass = candidate.pass;
  if (typeof pass !== 'boolean') {
    return null;
  }
  const feedback = typeof candidate.feedback === 'string' ? candidate.feedback.trim() : '';
  return { pass, feedback };
}

let cachedClient: OpenAI | null = null;
function getOpenAiClient(): OpenAI {
  if (!cachedClient) {
    cachedClient = new OpenAI({ apiKey: OPENAI_API_KEY.value() });
  }
  return cachedClient;
}

export const checkPracticeExplanation = onRequest(
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

    let input: ExplanationCheckRequest;
    try {
      input = parseExplanationInput(req.body);
    } catch {
      res.status(400).json({ error: 'Invalid request body.' });
      return;
    }

    try {
      const client = getOpenAiClient();
      const response = await client.responses.create({
        model: CHECKER_MODEL,
        instructions: SYSTEM_INSTRUCTION,
        input: buildExplanationPrompt(
          input.stem,
          input.userExplanation,
          input.correctAnswer,
        ),
        max_output_tokens: MAX_OUTPUT_TOKENS,
        reasoning: { effort: REASONING_EFFORT },
        text: {
          format: {
            type: 'json_schema',
            name: 'speedycat_explanation_check',
            schema: JSON_SCHEMA,
            strict: true,
          },
        },
      });

      const parsed = parseExplanationResponse(response.output_text ?? '');
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
