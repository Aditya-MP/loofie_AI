# Loofie AI service

The profile builder, brief reader (Model 1), matcher (Model 2) and quality check (Model 3).
Runs on our own machines: no external AI APIs. Models run on the GPU (`DEVICE=cuda` in `.env`).

Stack: Python 3.13 · uv · PyTorch 2.15 (CUDA 13.2) · sentence-transformers 6 · FastAPI ·
SQLAlchemy 2.1 · psycopg 3 · PostgreSQL 18 + pgvector · Alembic · pytest · ruff.
Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Backend contract: [docs/API.md](docs/API.md).

## Setup

```
docker compose up -d --wait          # PostgreSQL 18 + pgvector on port 5433
uv sync                              # creates .venv with exact versions from uv.lock (CUDA torch)
uv run alembic upgrade head          # create tables
uv run python -m app.ml.device       # should print: cuda <your GPU>
```

Every command below runs as `uv run loofie ...` (or activate `.venv` and drop `uv run`).
`uv run loofie help` lists them all.

## The real-data test, in one command

```
uv run loofie check ../data/Loofie_AI_Data_Collection.xlsx
```

Imports the sheet, builds profiles, runs the quality check, the brand-hire test and the brief
test, in that order, and stops at the first step that fails.

## Load data

```
uv run loofie import sheet ../data/Loofie_AI_Data_Collection.xlsx   # the data team's sheet
uv run loofie import sheet path/to/csv-folder                       # or one CSV per tab
uv run loofie import legacy ../data/Creator_Profiles_v2.csv         # first 101 creators
```

Bad rows are skipped and listed with their tab and row number. Add `--strict` to save nothing when
any row is bad. Grey EXAMPLE rows are skipped unless `--include-examples` is passed. Importing the
same file again updates rows instead of duplicating them, and a blank cell never erases a stored value.

## Build creator profiles (GPU)

```
uv run loofie profile build                    # every creator
uv run loofie profile build --handle someone   # just one
```

Prints how many profiles were built, what data is missing, and how the predicted niche compares
with the hand-entered category. Niche descriptions, language word lists and thresholds are in
`config/profile.toml`; bump the version there whenever you change them.

## Quality check (Model 3)

```
uv run loofie quality assess        # run after app.profile build
```

Labels every creator real / unsure / suspicious with a score and plain-English reasons, and
compares with the team's `quality_label`. Rules and thresholds are in `config/quality.toml`.
"Suspicious" sends a creator to ops for a human check; nothing is rejected automatically.

## Matcher (Model 2)

```
uv run loofie matcher match "gel sunscreen for oily skin" --category beauty_skincare --city Bangalore --budget 5000
uv run loofie matcher evaluate      # brand-hire test, needs Brands + Brand_Creator_Links
```

Filters out suspicious, inactive and off-category creators (and says how many and why), then ranks
by text similarity, niche, quality, language, budget, city and data completeness, with a
"why matched" line. Weights, filters and the estimated price bands are in `config/matcher.toml`.

The brand-hire test gives the matcher only a brand's product description and checks whether
the brand's real hires reach the top 10, against random, filters-only and biggest-first picks.
Creator profiles for the test use organic posts only, with @mentions removed, so a hire can't
be found from a sponsored caption naming the brand.

## Brief Reader (Model 1)

```
uv run loofie brief parse "need 3 girls blr for sunscreen reels, 15k total, by 5th dec"
uv run loofie brief evaluate                                # Briefs loaded by the importer
uv run loofie brief evaluate --csv tests/data/briefs_holdout.csv --as-of 2026-11-14
```

Rules only, no external AI. Every field comes with the words it was read from and a confidence;
a field with no evidence stays blank for the brand to fill. `evaluate` scores each field as
correct / wrong / missed / invented — invented must stay at 0. Word lists are in `config/brief.toml`.

`tests/data/briefs_dev.csv` was used to write the rules. `briefs_holdout.csv` was run once before
any fix (budget 67%, creators 87%), then used for fixes, so it is no longer unseen. The team's
Briefs tab is the real test.

## API for the backend

```
uv run loofie serve     # docs at http://localhost:8100/docs
```

Needs `AI_API_KEY` in `.env`; the backend sends it as `X-API-Key`. The contract, including when
to call each endpoint and which feedback events to send, is in [docs/API.md](docs/API.md).

## Tests

```
uv run pytest
uv run ruff check .
```

Database tests use a separate `loofie_ai_test` database, created automatically.

## Schema changes

Edit `app/db/models.py`, then:

```
uv run alembic revision --autogenerate -m "what changed"
uv run alembic upgrade head
```
