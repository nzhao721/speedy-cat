# SpeedyCAT AI answer-checker — evaluation

This is the spec-compliance evidence for SpeedyCAT's AI answer checker: every AI
output traces to a **named source**, is scored against a **held-out test set**,
and must beat a **simpler baseline**. Both apps also work fully with **AI off**.

## Named source

- **AI checker:** `openai:gpt-5.4-mini` (OpenAI *responses* API, strict
  `json_schema` structured output, low reasoning effort, bounded
  `max_output_tokens` — mirrors the cloud proxy setup).
- **Baseline:** `baseline:string-match+heuristic` — the deterministic AI-off
  path the apps fall back to:
  - **verdict** (correct/incorrect): case-insensitive whole-answer string match
    (HTML/media stripped, whitespace collapsed, NFC, lower-cased);
  - **honesty** (genuine attempt?): a conservative heuristic that flags only
    unmistakable non-attempts (blank, punctuation-only, a single character
    repeated, obvious keyboard-mash / filler tokens).

## Held-out test set

`fixtures.json` — 24 labeled forced-recall reveals, each `(front, typed,
expected)` with human labels `honest` and `correct`. It deliberately includes
cases a pure string/heuristic baseline gets wrong but a language model should
handle:

- **verdict**: synonyms (`Na+/K+ ATPase` ≈ *sodium-potassium pump*), paraphrase
  (`goes up` ≈ *increases*), abbreviations (`ATP` ≈ *adenosine triphosphate*),
  a leading article (`the nephron` ≈ *nephron*), extra text, and a misspelling;
- **honesty**: subtle gaming that isn't a keyboard mash (`no idea lol`,
  `just show me the answer`, `skip`, `blah blah blah`, `i like turtles`).

## How to run

```
python speedycat_eval/run_eval.py            # baseline (+ AI if a key is present)
python speedycat_eval/run_eval.py --markdown # Markdown report
```

The script loads the shared checker module (`anki/pylib/anki/speedycat_ai.py`)
directly by path — no build or `anki` install required.

## Baseline results (always runnable, no key)

Ran with the repo's Python on the 24-case held-out set:

| Metric | Baseline | AI |
| --- | --- | --- |
| Honesty accuracy | 79.2% | _needs key (live)_ |
| Verdict accuracy | 70.8% | _needs key (live)_ |
| Exact (both) | 50.0% | _needs key (live)_ |

The baseline misses exactly the cases it structurally cannot handle: 5 subtle
gaming inputs it treats as honest, and 7 semantically-correct answers (synonyms,
paraphrase, abbreviation, article, extra text, misspelling) it marks incorrect.

## Live AI results

**Requires the OpenAI key** (the user populates `SPEEDYCAT_OPENAI_API_KEY` or the
gitignored `.speedycat-openai.key`). With the key present, `run_eval.py` calls
`openai:gpt-5.4-mini` per case and prints the AI columns alongside the baseline;
the AI is expected to beat the baseline on both honesty and verdict accuracy
because those are exactly the synonym/paraphrase and subtle-gaming cases above.
Re-run the command once the key is in place to capture the live numbers here.

## AI-off guarantee

When the key is absent, the toggle is off, or the call errors, the apps use the
baseline path: the card still reveals with a case-insensitive verdict, and the
heuristic still drives the FSRS *Again* lock on a clear non-attempt. Nothing in
the review flow depends on the network.
