# Deploying core-service (open asks 1-8) — for the hosting owner

Recommended platform: AWS Mumbai (ap-south-1). The image and the app settings below are built and tested; the AWS
side is for the hosting owner. Prices in the research are estimates (about USD 330-510 a month): check them in the
AWS Pricing Calculator before committing.

## The image (ask 3) — done, tested

`core-service/Dockerfile`: Java 25, ffmpeg + ffprobe, the DejaVu watermark font, `nice`, non-root user. Build:
`./mvnw -B package && docker build -t core-service .`

Checked on 2026-11-19: a 1080x1920 test video became a watermarked 720x1280 H.264 + AAC preview inside the image,
as the non-root user, with the exact filter the app uses. The app started in about 6 s and readiness answered 200.
SIGTERM gave a graceful shutdown (exit 143 is the normal code for a stop by signal).

The distribution's ffmpeg includes libx264, which is GPL. It runs on our servers and is never given to users; the
lawyer confirms that position.

## Services

| Piece | Recommendation |
|---|---|
| API | ECS Fargate, 2 tasks of 1 vCPU / 2 GB, behind an Application Load Balancer |
| Video work | Same image. Today the worker runs inside each API task at low priority (`nice`). If transcoding ever slows the API, run a separate service with `LOOFIE_MEDIA_WORKER_ENABLED=true` and set it to `false` on the API service |
| Database | RDS PostgreSQL 18 Multi-AZ (one standby), automated backups with point-in-time recovery, 14-35 days kept |
| Files | S3 bucket in Mumbai, private, no public access. Not Cloudflare R2: it cannot keep data in India |
| Secrets | Secrets Manager for the database; Parameter Store for the rest; given to the task as environment variables |
| Logs | Kept at least 1 year (DPDP Rules, Rule 8(3)) |

## Environment variables (ask 2)

Required (the app refuses to start without them): `CORE_DB_URL` (with `sslmode=require`), `CORE_DB_USER`,
`CORE_DB_PASSWORD`, `LOOFIE_OTP_PEPPER`, `LOOFIE_TOTP_KEY`, `LOOFIE_APP_ORIGINS`. Also: `AI_FEEDBACK_URL` and
`AI_FEEDBACK_API_KEY` (https only; without them events wait in the outbox), the storage settings, and
`LOOFIE_BOOTSTRAP_FOUNDER_EMAIL` for the first start only. Generate the pepper and the TOTP key as long random
values; never reuse the development ones.

## Behind the load balancer (ask 5) — done, tested

The `prod` profile reads the client IP from `X-Forwarded-For`, trusted only from private addresses (the load balancer
inside the VPC). To narrow it to the VPC, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to a regex of the VPC range,
e.g. `10\.0\.\d{1,3}\.\d{1,3}`. Never set it empty: that trusts everyone, and the app refuses to start
(`ProxyTrustGuard`). `ForwardedClientIpTest` proves through real Tomcat that the login-code limit keys on the real
client and that a forged leading entry is ignored. After deploying, repeat that check once through the real load
balancer.

## Warm-up and deploys (ask 7)

- Load balancer health check: `/actuator/health/readiness` on port 8091 (healthy after 2 checks, 10 s apart).
- Load balancer slow start: 60-120 s, so a new task gets traffic gradually (a cold JVM gave short 503s in our tests).
- ECS: minimum healthy 100%, maximum 200%, deployment circuit breaker with rollback. Old tasks stop only after new
  ones are healthy.
- Shutdown: the app finishes requests within 30 s. Set the load balancer's deregistration delay to about 30 s and the
  ECS stop timeout to about 60 s (defaults are 300 s and 30 s, which do not match).
- Later: a Java 25 AOT cache in the image to start faster. Not built: it needs a training run against a database.

## Failover and kill tests (ask 8)

On the real setup, at the agreed load (reads p95 50 ms, writes 300 ms):
1. `aws rds reboot-db-instance --db-instance-identifier <id> --force-failover`. Expect 60-120 s of database errors
   (503 to users), then recovery on its own. The image already caches DNS for only 30 s, so the app finds the new
   primary; connections wait at most 5 s for the pool.
2. Stop one API task in the middle of a deploy (`aws ecs stop-task`).
3. Pass if: service recovers within 2 minutes, no committed write is lost, no stuck connections, drafts being
   transcoded are picked up again (they are leased).
Agree beforehand how many errors the failover minute may cost; the p95 gates cannot hold during it.

## Monitoring (ask 6)

Metrics are at `/actuator/prometheus` (port 8091). Start with alerts on: load balancer 5xx above 1% for 5 min; p95
above the gates for 10 min; unhealthy targets; database pool waiting (`hikaricp_connections_pending`); database CPU
above 80% or free storage below 20%; outbox age (`loofie.outbox.oldest.age.seconds`); drafts stuck (the app already
sends `DRAFT_PROCESSING_STUCK` to ops once a day); certificate expiry.
