# Loofie AI: architecture

The same shape large recommendation systems use (understand → retrieve → rank → re-rank →
learn from feedback), sized for hundreds to tens of thousands of creators, not billions. Every
stage is a separate module with one job, so any stage can be replaced without touching the others.

```
  Brand's notes                                          Creator data (sheet / Instagram API)
       │                                                            │
       ▼                                                            ▼
 ┌─────────────┐                                         ┌──────────────────────┐
 │ 1 UNDERSTAND│  app/brief        Model 1               │ PROFILE  app/profile │ niche, language, brands,
 │ notes→fields│  rules, evidence per field              │ BUILDER              │ medians, embedding (GPU)
 └──────┬──────┘                                         └──────────┬───────────┘
        │ brand confirms fields                                     ▼
        │                                                ┌──────────────────────┐
        │                                                │ TRUST    app/quality │ Model 3: real/unsure/
        │                                                │                      │ suspicious + activity
        ▼                                                └──────────┬───────────┘
 ┌──────────────────────────────────────────────────────────────────┴───────────┐
 │ 2 RETRIEVE   app/matcher/retrieve.py   one SQL query: hard filters +         │
 │              pgvector nearest neighbours → ~300 candidates, reasons counted   │
 ├───────────────────────────────────────────────────────────────────────────────┤
 │ 3 RANK       app/matcher/ranker.py     score each: similarity, niche,         │
 │              quality, language, budget, city, completeness, track record      │
 │              (track_record.py: past jobs from the events log) → "why matched" │
 ├───────────────────────────────────────────────────────────────────────────────┤
 │ 4 RE-RANK    app/matcher/rerank.py     business rules: competitor conflict,   │
 │              near-duplicate diversity, exploration slot for NEW creators      │
 └──────────────────────────────────────┬────────────────────────────────────────┘
                                        ▼
                               shortlist → ops → brand
                                        │ pick / reject + reason, booking, delivery,
                                        ▼ approval, rating, rebook
 ┌───────────────────────────────────────────────────────────────────────────────┐
 │ 5 FEEDBACK   app/feedback   every AI output and every human reaction, with the │
 │              model version and the exact suggestion it reacts to              │
 │              next: learning job → retune → evaluate → promote only if better   │
 └───────────────────────────────────────────────────────────────────────────────┘
```

## Rules every stage follows

- **The AI suggests, a person decides, the decision is logged.** Nothing is auto-rejected.
- **No hidden numbers.** Weights, thresholds and word lists live in `config/*.toml`, each with a
  `version` that is stored next to every output it produced.
- **Explainable.** Every field has evidence, every quality label a reason, every match a
  "why", every re-rank move a note.
- **Honest about missing data.** Blank stays blank; thin data gives "unsure", never "real".
- **Measured before trusted.** Each model has an evaluation command against human answer keys.
- **Learn from outcomes, not clicks.** The ranker will be trained on "booked, delivered on time,
  approved, rebooked", not on "brand picked", which rewards good-looking profiles.

## Folders

```
loofie-ai/
├── app/
│   ├── cli.py              one command: `loofie <command>`
│   ├── config.py           .env settings + load_toml() for config/*.toml
│   ├── api/                HTTP for the backend (contract: docs/API.md); rules.py = feedback checks
│   ├── mock.py             `loofie mock`: same contract, no GPU/database, for backend development
│   ├── brief/              Model 1 · understand
│   ├── profile/            profile builder · niche, language, signals, embedding
│   ├── quality/            Model 3 · trust
│   ├── matcher/            Model 2 · retrieve.py → ranker.py → rerank.py, evaluate.py
│   ├── feedback/           events log; learning job goes here
│   ├── instagram/          Instagram Login, fetch, weekly refresh, Meta deletion callbacks (F1)
│   ├── importer/           data team's sheet and old field CSV → database
│   ├── ml/                 GPU device + embedding model
│   └── db/                 tables (models.py) and connection
├── config/                 brief / profile / quality / matcher .toml
├── migrations/             database schema history (alembic)
├── contracts/examples/     real request + answer per call (scripts/make_examples.py writes them)
├── scripts/                make_examples.py
├── tests/                  helpers.py, data/ (brief test sets), one test file per module
└── docs/                   FLOW.md (story → calls), API.md (for the backend), ARCHITECTURE.md (this file)
```

## Where data lives

| Data | Table | Written by |
|---|---|---|
| What the data team or Instagram gave us | `brands`, `creators`, `posts`, `comments` | importer, `/ai/creators/profile` |
| Human answer keys | `creators.label_*`, `brand_creator_links`, `briefs.answer_key` | importer only |
| AI outputs (rebuilt any time) | `creator_profiles`, `creator_quality` | profile, quality |
| What happened | `events` (append-only) | API |
| Model versions | `model_versions` | learning job (next) |

AI outputs never overwrite answer keys, so every model can always be re-measured.

## Swapping a stage later

| Today | Later, when data allows | Touches only |
|---|---|---|
| Hand-set weights in the ranker | A model trained on job outcomes (300+ jobs) | `ranker.py` |
| Rule-based quality check | A classifier trained on ops labels (~100+) | `quality/rules.py` |
| Rules-based brief reader | An open model run on our own GPU | `brief/extract.py` |
| Sheet import | Instagram Business Discovery API | `importer/` |
