# Upstream provenance & credit

This project ("SpeedyCAT" — an MCAT study app) is a **brownfield fork** built on two
open-source projects. The inherited git history was intentionally **not** retained
(the project starts from a single fresh commit), but this file records the exact
upstream baselines for attribution and so future upstream diffs/merges remain possible.

## Desktop — Anki
- Upstream: https://github.com/ankitects/anki
- Base commit: `b00308e551576f5a71593a80f377bc1d28c6612e`
- Base date: 2026-06-29
- Location in this repo: `anki/`
- License: AGPL-3.0-or-later (some components BSD-3-Clause). See `anki/LICENSE`.

## Mobile — AnkiDroid
- Upstream: https://github.com/ankidroid/Anki-Android
- Base commit: `e6e941193fb4430dfa41d5931cdc6b762012ca30`
- Base date: 2026-06-29
- Location in this repo: `Anki-Android/`
- License: GPL-3.0 (backend AGPL-3.0; AnkiDroid API LGPL-3.0). See `Anki-Android/COPYING`.

## Credit
Anki is created by Damien Elmes and the Anki contributors. AnkiDroid is maintained by
the AnkiDroid open-source community. This project is independent and not endorsed by
them. All original licenses are preserved in the respective subdirectories.

## Reconnecting to upstream later (optional)
To compare against or merge from upstream without restoring history, add it as a remote
inside the relevant subfolder, e.g.:

    git -C anki remote add upstream https://github.com/ankitects/anki.git
    git -C anki fetch upstream
    # then diff your working tree against the recorded base commit above
