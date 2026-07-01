# SpeedyCAT flashcard eval — summary

Batches reported: **20/20**  ·  Cards evaluated: **5142** (expected 5142)

## Totals

- OK cards: **4936**
- Cards with issues: **206**
- By severity: error **2**, warning **20**, info **184**
- By check: Q/A correspondence **142**, well-formed **40**, markup **24**

## Most common failure types

- `cloze_reveals_image_only`: 126
- `image_only_answer`: 20
- `malformed_href`: 13
- `image_cloze_no_text_reveal`: 12
- `mnemonic_word_split`: 4
- `unrendered_cloze_artifact`: 3
- `grammar_missing_word`: 2
- `incomplete_sentence`: 2
- `garbled_text`: 2
- `cloze_hint_splits_font_tag`: 2
- `split_word_artifact`: 2
- `malformed_cloze_html`: 1
- `empty_href`: 1
- `no_cloze_deletion`: 1
- `cloze_spans_html_tags`: 1
- `stray_entity_in_href`: 1
- `missing_cloze_deletion`: 1
- `image_only_cloze`: 1
- `spelling_typo`: 1
- `split_word_markup`: 1

## Per-batch

| Batch | Cards | OK | Issues | Err | Warn | Info |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 258 | 241 | 17 | 0 | 5 | 12 |
| 2 | 258 | 142 | 116 | 1 | 3 | 112 |
| 3 | 257 | 252 | 5 | 0 | 1 | 4 |
| 4 | 257 | 252 | 5 | 0 | 1 | 4 |
| 5 | 257 | 244 | 13 | 0 | 0 | 13 |
| 6 | 257 | 250 | 7 | 1 | 0 | 6 |
| 7 | 257 | 255 | 2 | 0 | 1 | 1 |
| 8 | 257 | 253 | 4 | 0 | 0 | 4 |
| 9 | 257 | 239 | 18 | 0 | 1 | 17 |
| 10 | 257 | 255 | 2 | 0 | 2 | 0 |
| 11 | 257 | 251 | 6 | 0 | 2 | 4 |
| 12 | 257 | 255 | 2 | 0 | 2 | 0 |
| 13 | 257 | 256 | 1 | 0 | 0 | 1 |
| 14 | 257 | 257 | 0 | 0 | 0 | 0 |
| 15 | 257 | 256 | 1 | 0 | 1 | 0 |
| 16 | 257 | 255 | 2 | 0 | 0 | 2 |
| 17 | 257 | 256 | 1 | 0 | 1 | 0 |
| 18 | 257 | 253 | 4 | 0 | 0 | 4 |
| 19 | 257 | 257 | 0 | 0 | 0 | 0 |
| 20 | 257 | 257 | 0 | 0 | 0 | 0 |

## Worst offenders (error-severity, up to 60)

- b2 card 1554178686507 — markup/no_cloze_deletion: Cloze notetype card whose Text field contains no {{cN::}} deletion, so both question_text and answer_text render Anki's 'No cloze found on card' error instead of a studyable prompt; the card is broken.
- b6 card 1556150381511 — markup/missing_cloze_deletion: Cloze note has no {{c1::}} deletion in its Text field, so the card renders Anki's error message instead of a studyable Q/A.
