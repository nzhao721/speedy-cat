"""SpeedyCAT flashcard extraction (engine-based).

Run from the anki/ directory with the built venv python, e.g.:

    set SKIP_RUN=1
    out\\pyenv\\Scripts\\python.exe ..\\content\\flashcards\\_tools\\extract_cards.py

It:
  1. ensures both deck packages are present (downloads Pankow from Google
     Drive via the >100MB confirm handshake if missing),
  2. imports both .apkg files into a throwaway collection using the real Anki
     engine (our own Collection.import_builtin_deck backend method),
  3. renders every CARD's question/answer through the engine and flattens to
     one self-contained record per card,
  4. writes content/flashcards/cards.json.

No GUI / aqt is imported; this is pure pylib (the Rust-backed engine).
"""

from __future__ import annotations

import html
import http.cookiejar
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.request
from urllib.parse import urlencode

# --- locate things -----------------------------------------------------------
# cwd is expected to be the anki/ repo dir; make anki importable from source.
ANKI_DIR = os.path.dirname(os.path.abspath(os.path.join(os.getcwd(), "x")))
sys.path[0:0] = ["pylib", "out/pylib"]

REPO_ROOT = os.path.abspath(os.path.join(os.getcwd(), ".."))
PKG_DIR = os.path.join(REPO_ROOT, "content", "flashcards", "_packages")
OUT_JSON = os.path.join(REPO_ROOT, "content", "flashcards", "cards.json")

MILEDOWN = os.path.join(PKG_DIR, "MileDown.apkg")
PANKOW = os.path.join(PKG_DIR, "Pankow.apkg")
PANKOW_GDRIVE_ID = "1Vy6zqbdHcdBaAGKC_RPaDy-yJSkZ5IVP"
MILEDOWN_URL = (
    "https://github.com/DarrenPHS/DarrenPHS/releases/download/1.1/MileDown.apkg"
)
_UA = "SpeedyCAT/1.0 (deck extractor)"
_MIN_BYTES = 1_000_000
_CHUNK = 256 * 1024


def _looks_like_pkg(path: str) -> bool:
    try:
        if not os.path.isfile(path) or os.path.getsize(path) < _MIN_BYTES:
            return False
        with open(path, "rb") as fh:
            return fh.read(2) == b"PK"
    except OSError:
        return False


def _http_to_file(opener: urllib.request.OpenerDirector, url: str, dest: str) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    part = dest + ".part"
    with opener.open(req, timeout=180) as resp, open(part, "wb") as fh:
        shutil.copyfileobj(resp, fh, _CHUNK)
    os.replace(part, dest)


def _gdrive_confirm_url(page: str) -> str:
    m = re.search(r'<form[^>]*action="([^"]+)"', page, re.IGNORECASE)
    if not m:
        raise RuntimeError("could not parse Google Drive confirmation page")
    action = html.unescape(m.group(1))
    fields: dict[str, str] = {}
    for tag in re.findall(r"<input\b[^>]*>", page, re.IGNORECASE):
        name = re.search(r'name="([^"]*)"', tag)
        if not name:
            continue
        val = re.search(r'value="([^"]*)"', tag)
        fields[html.unescape(name.group(1))] = html.unescape(val.group(1)) if val else ""
    sep = "&" if "?" in action else "?"
    return action + sep + urlencode(fields)


def _download_gdrive(file_id: str, dest: str) -> None:
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
    )
    url = f"https://drive.usercontent.google.com/download?id={file_id}&export=download"
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    with opener.open(req, timeout=180) as resp:
        ctype = resp.headers.get("Content-Type", "").lower()
        if "text/html" in ctype:
            confirm = _gdrive_confirm_url(resp.read().decode("utf-8", "ignore"))
        else:
            part = dest + ".part"
            with open(part, "wb") as fh:
                shutil.copyfileobj(resp, fh, _CHUNK)
            os.replace(part, dest)
            return
    _http_to_file(opener, confirm, dest)


def ensure_packages() -> None:
    os.makedirs(PKG_DIR, exist_ok=True)
    if not _looks_like_pkg(MILEDOWN):
        print(f"MileDown missing/invalid, downloading -> {MILEDOWN}", flush=True)
        _http_to_file(urllib.request.build_opener(), MILEDOWN_URL, MILEDOWN)
    if not _looks_like_pkg(PANKOW):
        print(f"Pankow missing, downloading via gdrive -> {PANKOW}", flush=True)
        _download_gdrive(PANKOW_GDRIVE_ID, PANKOW)
    for label, path in (("MileDown", MILEDOWN), ("Pankow", PANKOW)):
        if not _looks_like_pkg(path):
            raise SystemExit(f"{label} package invalid after download: {path}")
        print(f"OK {label}: {os.path.getsize(path):,} bytes", flush=True)


_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")


def to_text(s: str) -> str:
    s = s.replace("\u00a0", " ")
    s = re.sub(r"(?is)<style.*?</style>", " ", s)
    s = re.sub(r"(?is)<script.*?</script>", " ", s)
    s = _TAG_RE.sub(" ", s)
    s = html.unescape(s)
    return _WS_RE.sub(" ", s).strip()


def main() -> None:
    ensure_packages()

    from anki.collection import Collection

    tmpdir = tempfile.mkdtemp(prefix="speedycat_extract_")
    col_path = os.path.join(tmpdir, "collection.anki2")
    print(f"creating temp collection: {col_path}", flush=True)
    col = Collection(col_path)

    sources = [("miledown", MILEDOWN), ("pankow", PANKOW)]
    card_source: dict[int, str] = {}
    seen: set[int] = set()
    try:
        for key, path in sources:
            print(f"importing {key} via engine: {path}", flush=True)
            res = col.import_builtin_deck(
                package_path=path,
                deck_key=key,
                parent_deck="SpeedyCAT MCAT",
                force=True,
            )
            print(f"  imported={res.imported} note_count={res.note_count}", flush=True)
            now = set(int(c) for c in col.find_cards(""))
            for cid in now - seen:
                card_source[cid] = key
            seen = now

        records = []
        all_cids = list(col.find_cards(""))
        print(f"rendering {len(all_cids)} cards ...", flush=True)
        for i, cid in enumerate(all_cids):
            card = col.get_card(cid)
            note = card.note()
            try:
                nt = note.note_type() or {}
            except AttributeError:
                nt = note.model() or {}
            try:
                tmpl = card.template()
                tmpl_name = tmpl.get("name", "")
            except Exception:
                tmpl_name = ""
            q = card.question()
            a = card.answer()
            fields = dict(note.items())
            records.append(
                {
                    "card_id": int(cid),
                    "note_id": int(note.id),
                    "source_deck_key": card_source.get(int(cid), ""),
                    "deck": col.decks.name(card.did),
                    "notetype": nt.get("name", ""),
                    "template": tmpl_name,
                    "ord": int(card.ord),
                    "question_html": q,
                    "answer_html": a,
                    "question_text": to_text(q),
                    "answer_text": to_text(a),
                    "fields": fields,
                    "tags": list(note.tags),
                }
            )
            if (i + 1) % 500 == 0:
                print(f"  {i + 1}/{len(all_cids)}", flush=True)
    finally:
        col.close()

    note_ids = {r["note_id"] for r in records}
    by_deck_key: dict[str, int] = {}
    for r in records:
        by_deck_key[r["source_deck_key"]] = by_deck_key.get(r["source_deck_key"], 0) + 1

    payload = {
        "meta": {
            "title": "SpeedyCAT built-in deck — flattened card extraction",
            "generatedOn": "2026-06-30",
            "method": "engine render via anki pylib (Collection.import_builtin_deck + Card.question/answer)",
            "card_count": len(records),
            "note_count": len(note_ids),
            "by_source_deck_key": by_deck_key,
            "schema": {
                "one_record_per": "card",
                "fields": [
                    "card_id", "note_id", "source_deck_key", "deck", "notetype",
                    "template", "ord", "question_html", "answer_html",
                    "question_text", "answer_text", "fields", "tags",
                ],
            },
        },
        "cards": records,
    }
    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False)
    print(
        f"WROTE {OUT_JSON}: {len(records)} cards / {len(note_ids)} notes; "
        f"by_source={by_deck_key}",
        flush=True,
    )
    shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    main()
