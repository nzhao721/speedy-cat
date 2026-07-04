# SpeedyCAT AI answer-checker cloud proxy

Fresh installs on desktop and mobile can use the optional AI flashcard answer checker **without** placing a local `.speedycat-openai.key` file. The OpenAI key lives server-side in Firebase Secret Manager on a **SpeedyCAT-owned** Firebase project (fully independent of any other repo).

Source lives in this repo under `functions/` (`checkSpeedycatAnswer`).

## Endpoint

Default URL (baked into both apps after you deploy to project `speedycat-mcat`):

```
https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer
```

Override for staging/emulator or a different Firebase project:

- Desktop: `SPEEDYCAT_AI_PROXY_URL` environment variable
- Mobile: `SPEEDYCAT_AI_PROXY_URL` environment variable (emulator/adb)
- Set to empty string to disable the proxy (local key only)

## Precedence (proxy > local key > static fallback)

1. **Cloud proxy** — POST JSON `{ "front", "typed", "expected" }` to the endpoint above. Named source: `openai/gpt-5.4-mini via speedycat-proxy`.
2. **Local OpenAI key** — `SPEEDYCAT_OPENAI_API_KEY` env var, or `.speedycat-openai.key` file (desktop: repo root / cwd; mobile: `filesDir/.speedycat-openai.key`). Calls OpenAI directly when the proxy is unavailable. Named source: `openai:gpt-5.4-mini`.
3. **Static fallback** — case-insensitive string match + conservative gaming heuristic. Named source: `baseline:string-match+heuristic`.

AI is **on by default** (`speedycatAiChecker` preference). Users can turn it off in Reviewing preferences or the reviewer context menu. Either AI path failing falls back to (3).

## Deploy (one-time)

All commands run from the **speedrun repo root** (`C:\alpha_ai\speedrun`).

### 1. Create Firebase project

1. Open [Firebase Console](https://console.firebase.google.com/) → **Add project**.
2. Use project id **`speedycat-mcat`** (matches the baked-in `DEFAULT_PROXY_URL`). If you pick a different id, override the URL in both apps via `SPEEDYCAT_AI_PROXY_URL` at build/run time.
3. Enable the **Blaze** (pay-as-you-go) plan — Cloud Functions with secrets requires it.

### 2. Configure CLI

```bash
npm install -g firebase-tools
firebase login
cp .firebaserc.example .firebaserc
# Edit .firebaserc if your project id differs from speedycat-mcat
```

`.firebaserc` is gitignored; never commit real project credentials.

### 3. Store OpenAI key in Secret Manager

```bash
npx firebase-tools@latest functions:secrets:set OPENAI_API_KEY
# Paste your OpenAI API key when prompted (never commit it)
```

### 4. Build and deploy the function

```bash
cd functions
npm install
npm run build
npm test
cd ..
npx firebase-tools@latest deploy --only functions:checkSpeedycatAnswer
```

Deploy prints the HTTPS URL. It should match:

`https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer`

### 5. Verify

```bash
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"front":"What is the powerhouse of the cell?","typed":"mitochondria","expected":"Mitochondria"}' \
  https://us-central1-speedycat-mcat.cloudfunctions.net/checkSpeedycatAnswer
```

Expect HTTP 200 with JSON like:

```json
{
  "honest_attempt": true,
  "verdict": "correct",
  "reason": "Matches the expected answer."
}
```

### 6. Use in apps

No local key needed. **AI answer check** is on by default; desktop and mobile call the proxy first, then a local key if configured.

## Request / response

**POST** `Content-Type: application/json`

```json
{
  "front": "What is the powerhouse of the cell?",
  "typed": "mitochondria",
  "expected": "Mitochondria"
}
```

**200** response:

```json
{
  "honest_attempt": true,
  "verdict": "correct",
  "reason": "Matches the expected answer."
}
```

Any HTTP error or malformed body → client uses the static fallback.

## Local development

```bash
cd functions
npm run build
npm test
# Optional emulator (requires .firebaserc + secrets access):
npm run serve
```

Point the app at the emulator with:

```
SPEEDYCAT_AI_PROXY_URL=http://127.0.0.1:5001/speedycat-mcat/us-central1/checkSpeedycatAnswer
```
