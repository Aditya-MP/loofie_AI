# Running the AI service

For whoever deploys and looks after it. Day-to-day commands are in the README.

## Deploy on a GPU server

Any Linux machine with an NVIDIA GPU (a cloud T4 or L4 is plenty), a driver that supports
CUDA 13, Docker, and the NVIDIA Container Toolkit.

```
git clone -b backend-a https://github.com/Aditya-MP/loofie_AI.git && cd loofie_AI/loofie-ai
cp .env.example .env          # then set AI_API_KEY and a real database password
docker compose --profile server up -d --build
curl localhost:8100/health    # {"status":"ok","device":"cuda",...}
```

The API container applies database migrations on start, then serves on port 8100. The first
start downloads the e5 model (~470 MB) into the `models` volume; later starts reuse it.

**Before real brand data goes on it:**
- Change `POSTGRES_PASSWORD` in `docker-compose.yml` and the URL in `.env` from the dev default.
- Don't expose port 5433 (the database) to the internet. Only the backend should reach 8100,
  through a private network or a firewall rule.
- Put HTTPS in front of 8100 (the cloud load balancer, or Caddy / nginx).
- Give the backend its own `AI_API_KEY`, and change it if it ever leaks.

## Every push is tested

`.github/workflows/loofie-ai.yml` runs the linter, migrations and the full test suite against a
real PostgreSQL 18 on every push to `backend-a`. GPU tests skip there (no GPU on GitHub's
runners), so run `uv run pytest` on the GPU machine before a release too.

## Changing a model's settings safely

Never edit `config/<model>.toml` on the server by hand. Copy it, change it, bump `version`, then:

```
uv run loofie models promote matcher my-candidate.toml
```

It scores the live and candidate versions on the same evaluation data and goes live only if the
candidate scores at least as well **and** passes its guardrail:

| Model | Score | Guardrail (must pass) |
|---|---|---|
| brief | share of fields correct on the Briefs answer key | 0 invented fields |
| quality | agreement with the team's quality_label | ≤ 10% of team-verified "real" creators flagged suspicious |
| matcher | brand-hire test recall@10 | beats filters-only (no-AI) picks |
| profile | beauty-vs-not agreement with hand labels | none |

No evaluation data → refused unless `--force`. Every decision is recorded in `model_versions`.
The old file goes to `config/history/`; to undo: `uv run loofie models rollback matcher`.
Restart the API after a promote or rollback so it reads the new file.

## Weekly check

```
uv run loofie report
```

Watch **"ops picks in matcher top"** (shadow mode): the share of creators ops showed brands that
the matcher had already suggested. It should rise over time. Also watch which brief fields
brands fix most (Model 1's weak spots) and the top reject reasons.

## Backups

```
uv run loofie backup                     # → backups/loofie_ai-<date>.dump, keeps the last 14
uv run loofie restore backups/<file>.dump --yes
```

Backups contain real creator data: they're git-ignored, never commit or share them. Schedule
`loofie backup` daily (cron on Linux, Task Scheduler on Windows). On a managed cloud database,
also turn on the provider's automatic backups. **Test a restore once a month**; an untested
backup isn't a backup.

## Logs

One JSON line per API call: `request_id`, `path`, `status`, `ms`. Bodies (briefs, creator
data) are never logged. The backend can send `X-Request-ID` to trace one call across both
services; an unexpected error returns `{"detail": "internal error", "request_id": ...}`, so
quote that id when reporting a problem. Ship the container logs to whatever the cloud provides
(CloudWatch, Cloud Logging, Grafana Loki) and alert on `status >= 500` and on `ms > 2000`.

## Not done yet

- Rate limiting per caller (one shared key today; add when there's more than one caller).
- Automatic retraining (the learning job), once real feedback exists. It will hand its
  candidates to `loofie models promote`, so it can't put an untested model live.
