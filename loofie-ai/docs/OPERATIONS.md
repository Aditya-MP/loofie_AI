# Running the AI service

For whoever deploys and looks after it. Day-to-day commands are in the README.

## Deploy on a GPU server

Any Linux machine with an NVIDIA GPU (a cloud T4 or L4 is plenty), a driver that supports
CUDA 13, Docker, and the NVIDIA Container Toolkit.

```
git clone -b backend-a https://github.com/Aditya-MP/loofie_AI.git && cd loofie_AI/loofie-ai
cp .env.example .env          # then set AI_API_KEYS and a real database password
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
- Give each caller its own key in `AI_API_KEYS` (`backend:KEY`). To rotate: add the new key
  under the same name (`backend:OLD,backend:NEW`), restart, let the backend switch, then remove
  the old one and restart again. The request log's `caller` field shows who is still on which.
  Generate keys with `python -c "import secrets; print(secrets.token_urlsafe(32))"` and send
  them through a password manager, never chat or git.

## Pilot server: this PC + Cloudflare Tunnel

Until there's a cloud GPU server, the pilot runs on the development PC (GTX 1650). A Cloudflare
Tunnel gives it a public https address without opening ports: `cloudflared` connects outwards.

```powershell
.\scripts\pilot.ps1          # database + AI service on the GPU + tunnel; prints the public URL
.\scripts\pilot.ps1 -Stop    # stop the API and the tunnel
```

**Limits (say them to the backend):** down whenever the PC is off, asleep or restarting; one
GPU; no rate limiting. Fine for integration testing and the first testers, not for paying brands.
Set Windows power settings to "never sleep" while it's in use. Logs: `%TEMP%\loofie-pilot`.

**Quick tunnel (default):** no account, but a new random `*.trycloudflare.com` URL on every start.
Good enough for the backend to test against; it can't be used for Instagram Login, because Meta
needs the redirect URL registered once and exactly.

**Named tunnel (fixed address, needed for Instagram Login).** Needs a free Cloudflare account and
a domain whose DNS is on Cloudflare (a `.in` domain costs a few hundred rupees a year). One time:

```powershell
cloudflared tunnel login                              # opens the browser, pick the domain
cloudflared tunnel create loofie-ai                   # writes credentials to %USERPROFILE%\.cloudflared
cloudflared tunnel route dns loofie-ai ai.<your-domain>
```

Create `%USERPROFILE%\.cloudflared\config.yml`:

```yaml
tunnel: loofie-ai
credentials-file: C:\Users\<you>\.cloudflared\<tunnel-id>.json
ingress:
  - hostname: ai.<your-domain>
    service: http://127.0.0.1:8100
  - service: http_status:404
```

Then in `.env`: `PILOT_TUNNEL=loofie-ai` and
`IG_REDIRECT_URI=https://ai.<your-domain>/auth/instagram/callback`, and run `pilot.ps1`. The
tunnel credentials file is a secret: never commit it. Moving to a cloud server later keeps the
same address: run `cloudflared` there with the same tunnel, or point the DNS at the server.

## Instagram Login

1. In the Meta developer app: add the **Instagram** product → *API setup with Instagram login*.
   Copy the Instagram app ID and secret into `IG_APP_ID` / `IG_APP_SECRET`.
2. Register these URLs in the app (all on this service's public https address):
   - OAuth redirect: `/auth/instagram/callback` (must equal `IG_REDIRECT_URI` exactly)
   - Deauthorize callback: `/auth/instagram/deauthorize`
   - Data deletion request URL: `/auth/instagram/data-deletion`
3. `uv run loofie instagram new-key` → `IG_TOKEN_KEY`. Back it up with the other secrets;
   losing or changing it means every creator has to log in again.
4. Until App Review passes, only people added as **testers** in the Meta app can log in.
   App Review needs: `instagram_business_basic` + `instagram_business_manage_insights`, a
   privacy policy URL, and a screen recording of the login flow. **[VERIFY]** current
   requirements in the Meta docs.
5. Schedule `uv run loofie instagram maintain` **daily** (renews tokens ~10 days before they
   expire, re-fetches anyone not synced for 7 days). Exit code 1 = some fetches failed;
   `uv run loofie instagram status` shows who and why.

Endpoints and field names live in `config/instagram.toml`. When Meta announces a version
deprecation, bump `graph_version` there and run `tests/test_instagram.py` plus one real login.

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

## Pilot log: recording jobs run by hand

While pilot jobs run on WhatsApp there is no app to send events, so ops records them in a sheet and
it is loaded into the events log as if the app had sent them. **Without it the pilot teaches the
models nothing.**

```
uv run loofie pilot template pilot_log.xlsx       # once: blank sheet with dropdowns + "How to fill" tab
uv run loofie pilot import pilot_log.xlsx --dry-run   # check: lists every bad row, changes nothing
uv run loofie pilot import pilot_log.xlsx         # load (Google Sheets: download the Log tab as .csv)
```

- **One row per thing that happened**: Date, Brief, Brand, Creator, What happened, Detail, Reason
  code. "What happened" and "Reason code" are dropdowns. The "How to fill" tab explains each one
  and has a worked example.
- **Same checks as the app** (`/ai/feedback` rules), so pilot data and app data look the same.
- **All or nothing**: one bad row and nothing loads; every bad row is listed with what to fix.
- **Re-import any time** (weekly is fine). It syncs: edited rows are replaced, deleted rows removed,
  unchanged rows left alone. To fix a mistake, edit the row. Only events that came from the sheet
  are ever touched.
- **Revisions are counted for you**: one "Revision asked" row each time; "Delivered" adds them up.
- **Dropped out needs a reason code**: it decides whose fault it was, so brand and courier problems
  never count against the creator.
- **Never type** phone numbers, emails, UPI ids, bank or ID numbers: rows with them are refused.
- The creator must already be in Loofie (`POST /ai/creators/profile` or the data sheet import).
- Keep the sheet in the team drive, **not in this repo** (it names real creators and brands).
- When the app sends events itself, stop adding rows; the imported pilot events stay.

## Weekly check

```
uv run loofie report
```

Watch **"ops picks in matcher top"** (shadow mode): the share of creators ops showed brands that
the matcher had already suggested. It should rise over time. Also watch which brief fields
brands fix most (Model 1's weak spots) and the top reject reasons. The report also counts real
prices recorded (the follower-based rate estimate is replaced after 20-30) and how many events came
from the pilot log.

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

- **A real Instagram login.** Everything is tested against a fake Meta server built from
  Meta's documented responses; the first real tester login is the check that matters.
- Rate limiting per caller (one shared key today; add when there's more than one caller).
- Automatic retraining (the learning job), once real feedback exists. It will hand its
  candidates to `loofie models promote`, so it can't put an untested model live.
