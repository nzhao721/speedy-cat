# SpeedyCAT AI checker — alternative-answer acceptance eval

How many **distinct** alternative answers (excluding case-only changes) does the
AI checker accept for the same flashcard question?

## Setup

- **Named source:** `openai/gpt-5.4-mini via speedycat-proxy` or `openai:gpt-5.4-mini` (via `run_check`)
- **Cards:** 6 flashcards from `acceptance_cards.json`
- **Variants generated:** synonyms, paraphrases, abbreviations, typos,
  punctuation/spacing tweaks, and wrong-answer controls
- **Variants tested:** 141 (case-only duplicates excluded)

## Aggregate stats

| Metric | Value |
| --- | --- |
| Variants tested | 141 |
| Accepted (any call) | 121 (85.8%) |
| **Distinct alternatives accepted** (excl. case) | **121** |
| Mean distinct alternatives per card | 20.2 |
| API errors (fell back to baseline) | 0 |
| Wrong-answer controls tested | 16 |
| Wrong-answer controls incorrectly accepted | 0 |

## Per-card results

### mitochondria

- **Front:** What organelle is the powerhouse of the cell?
- **Expected:** Mitochondria
- Variants tested: 21
- **Distinct alternatives accepted:** 16
- Accepted (all calls): 16

**Accepted alternatives:**

- *extra_words:* `the mitochondria`; `mitochondria - powerhouse of the cell`
- *synonym:* `mitochondrion`; `mitochondria organelle`
- *typo:* `mitochondira`; `mitocondria`; `mitochondrea`; `mitchondria`; `mitocondrea`
- *typo_auto:* `iMtochondria`; `itochondria`; `Mtiochondria`; `Mtochondria`; `Miotchondria`; `Miochondria`; `Mitochoondria`

*Wrong controls correctly rejected:* 3

### na_k_pump

- **Front:** Common biochemical name for the Na+/K+ pump?
- **Expected:** Sodium-potassium pump
- Variants tested: 22
- **Distinct alternatives accepted:** 20
- Accepted (all calls): 20

**Accepted alternatives:**

- *abbrev:* `Na/K ATPase`
- *synonym:* `Na+/K+ ATPase`; `sodium potassium pump`; `Na-K pump`; `sodium/potassium pump`
- *typo:* `sodum-potassium pump`; `sodium potasium pump`; `sodium-potassium pmp`
- *typo_auto:* `oSdium-potassium pump`; `odium-potassium pump`; `Sdoium-potassium pump`; `Sdium-potassium pump`; `Soidum-potassium pump`; `Soium-potassium pump`; `Sodium-pootassium pump`; `Sodium-potassium upmp`; `Sodium-potassium ump`; `Sodium-potassium pmup`; `Sodium-potassium pupm`; `Sodium-potassium pup`

*Wrong controls correctly rejected:* 2

### depolarization

- **Front:** During depolarization, the membrane potential does what?
- **Expected:** increases
- Variants tested: 19
- **Distinct alternatives accepted:** 16
- Accepted (all calls): 16

**Accepted alternatives:**

- *paraphrase:* `membrane potential increases`; `gets more positive`
- *synonym:* `goes up`; `rises`; `becomes more positive`; `less negative`
- *typo:* `increaes`; `incrases`; `increse`
- *typo_auto:* `nicreases`; `ncreases`; `icnreases`; `icreases`; `inrceases`; `inreases`; `increeases`

*Wrong controls correctly rejected:* 3

### atp

- **Front:** What is the cell's main energy currency molecule?
- **Expected:** Adenosine triphosphate
- Variants tested: 22
- **Distinct alternatives accepted:** 19
- Accepted (all calls): 19

**Accepted alternatives:**

- *abbrev:* `ATP`
- *extra_words:* `ATP (adenosine triphosphate)`
- *synonym:* `adenosine triphosphate molecule`
- *typo:* `adenosine triphospate`; `adenosene triphosphate`; `adenosine triphosphat`
- *typo_auto:* `dAenosine triphosphate`; `denosine triphosphate`; `Aednosine triphosphate`; `Aenosine triphosphate`; `Adneosine triphosphate`; `Adnosine triphosphate`; `Adenoosine triphosphate`; `Adenosine rtiphosphate`; `Adenosine riphosphate`; `Adenosine tirphosphate`; `Adenosine tiphosphate`; `Adenosine trpihosphate`; `Adenosine trphosphate`

*Wrong controls correctly rejected:* 3

### krebs

- **Front:** The Krebs cycle is also known as?
- **Expected:** Citric acid cycle (TCA cycle)
- Variants tested: 41
- **Distinct alternatives accepted:** 38
- Accepted (all calls): 38

**Accepted alternatives:**

- *abbrev:* `CAC`
- *extra_words:* `the citric acid cycle`
- *synonym:* `citric acid cycle`; `TCA cycle`; `tricarboxylic acid cycle`
- *typo:* `citric acid cyle`; `citrc acid cycle`
- *typo_auto:* `iCtric acid cycle (TCA cycle)`; `itric acid cycle (TCA cycle)`; `Ctiric acid cycle (TCA cycle)`; `Ctric acid cycle (TCA cycle)`; `Cirtic acid cycle (TCA cycle)`; `Ciric acid cycle (TCA cycle)`; `Citrric acid cycle (TCA cycle)`; `Citric caid cycle (TCA cycle)`; `Citric cid cycle (TCA cycle)`; `Citric aicd cycle (TCA cycle)`; `Citric aid cycle (TCA cycle)`; `Citric acdi cycle (TCA cycle)`; `Citric acd cycle (TCA cycle)`; `Citric acid yccle (TCA cycle)`; `Citric acid ycle (TCA cycle)`; `Citric acid ccyle (TCA cycle)`; `Citric acid ccle (TCA cycle)`; `Citric acid cylce (TCA cycle)`; `Citric acid cyle (TCA cycle)`; `Citric acid cycle T(CA cycle)`; `Citric acid cycle TCA cycle)`; `Citric acid cycle (CTA cycle)`; `Citric acid cycle (CA cycle)`; `Citric acid cycle (TAC cycle)`; `Citric acid cycle (TA cycle)`; `Citric acid cycle (TCA yccle)`; `Citric acid cycle (TCA ycle)`; `Citric acid cycle (TCA ccyle)`; `Citric acid cycle (TCA ccle)`; `Citric acid cycle (TCA cylce)`; `Citric acid cycle (TCA cyle)`

*Wrong controls correctly rejected:* 2

### nephron

- **Front:** What is the functional filtering unit of the kidney?
- **Expected:** Nephron
- Variants tested: 16
- **Distinct alternatives accepted:** 12
- Accepted (all calls): 12

**Accepted alternatives:**

- *extra_words:* `the nephron`; `a nephron`
- *typo:* `nephronn`; `neprhon`; `nephron unit`
- *typo_auto:* `eNphron`; `ephron`; `Npehron`; `Nphron`; `Nehpron`; `Nehron`; `Nephhron`

*Wrong controls correctly rejected:* 3

## Method notes

- Variants that normalize to the same text as the expected answer (case-insensitive)
  are not counted as alternatives.
- Case-only variants are never generated; spacing/punctuation/synonym/typo variants are.
- Re-run: `python speedycat_eval/run_acceptance_eval.py`
