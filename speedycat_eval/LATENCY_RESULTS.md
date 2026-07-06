# SpeedyCAT latency evaluation

- **Date:** 2026-07-05 23:29 UTC
- **Host:** Windows 11 (AMD64)
- **Python:** 3.14.0
- **Samples per AI surface:** 20 (after 2 warmup discard each)
- **Answer checker proxy:** `https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer`
- **Explanation checker proxy:** `https://us-central1-speedycat-mcat.cloudfunctions.net/checkPracticeExplanation`
- **Local OpenAI key present:** yes (fallback available)

## AI endpoint latency

Answer checker uses `run_check()` (proxy first, then direct key). Fixtures are cases that miss the deterministic string match in production. Explanation checker POSTs `{stem, userExplanation, correctAnswer}` to the proxy.

| Surface | n | success | min (ms) | median (ms) | mean (ms) | p95 (ms) | max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Answer checker | 20 | 20 | 1145.4 | 1430.0 | 1465.7 | 2037.7 | 2166.6 |
| Explanation checker | 20 | 20 | 1548.2 | 1937.3 | 2115.0 | 2728.9 | 4788.3 |

### Call sources (successful samples)

- Answer checker: `{"openai/gpt-5.4-mini via speedycat-proxy": 20}`
- Explanation checker: `{"openai/gpt-5.4-mini via speedycat-proxy": 20}`

## Startup / readiness

| Surface | n | success | min (ms) | median (ms) | mean (ms) | p95 (ms) | max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Cold import `speedycat_ai` (subprocess) | 3 | 3 | 28.9 | 30.7 | 30.7 | 32.4 | 32.4 |
| Warm import `speedycat_ai` (in-process) | 3 | 3 | 2.5 | 3.2 | 3.1 | 3.6 | 3.6 |
| First successful checker call (warm process) | 1 | 1 | 1539.1 | 1539.1 | 1539.1 | 1539.1 | 1539.1 |

Cold import spins a fresh Python subprocess loading the module from disk. Checker-ready time includes network RTT for the first live proxy call after import.

## Sync

Full collection sync requires Anki credentials, a built `rsbridge`, and an open collection — not run here. Instead we measured **AnkiWeb sync host connectivity** (HTTP HEAD to `https://sync.ankiweb.net/`), which approximates network path latency only.

| Surface | n | success | min (ms) | median (ms) | mean (ms) | p95 (ms) | max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AnkiWeb sync host HEAD | 5 | 5 | 645.0 | 822.7 | 819.7 | 1002.5 | 1002.5 |

## Android cold start

Timed `adb shell am start -W` after `force-stop` on `com.ichi2.anki.debug`.

| Surface | n | success | min (ms) | median (ms) | mean (ms) | p95 (ms) | max (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| AnkiDroid launch (-W) | 2 | 2 | 3477.4 | 3843.0 | 3843.0 | 4208.7 | 4208.7 |

## Notes

- **Cold vs warm:** First AI sample after warmup may still be warmer than a true cold start because TLS sessions and Firebase/Google Cloud paths reuse connections.
- **Network variance:** Cloud Function → OpenAI adds variable latency; re-run on different networks for production SLO planning.
- **Production gating:** The live reviewer only calls the answer checker when the deterministic verdict is incorrect; this harness always invokes the checker API for consistent latency measurement.
- **Secrets:** No API keys or auth tokens are recorded in this report.
