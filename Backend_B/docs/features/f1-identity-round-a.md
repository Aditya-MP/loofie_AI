# F1 round A: identity core (steps 1-15)

Date: 2026-11-18. Status: **built, tested and measured (sections 12-15); plan approved 2026-11-18 with D1-D3 as recommended.** Real SMS / email delivery is blocked on providers.
Agreed flow: https://claude.ai/artifact/MPwtSeCJnBt35soCa1AcsK (team answers to the 24 questions, 2026-11-17).
Rounds: **A identity core (this doc)**, B brand organisations, C creator onboarding + Instagram.

## 1. Business purpose

Production refuses every request today (`DenyAllActorResolver`). Nobody can use Loofie until people can prove who they
are. Round A gives creators, brands and staff a real login and turns that login into the `Actor` the existing features
already check. It must:

- let a creator in with a phone OTP and a brand user with an email OTP, with no passwords to leak or reset;
- keep staff (who can refund money and suspend people) behind a second factor;
- stop bots from creating accounts or spending the SMS budget;
- record consent (DPDP Act) and every staff action on people (CERT-In, disputes);
- let ops block a fraudster immediately.

Round A alone does **not** let brands work: a brand role needs an organisation (`Actor` requires `orgId` for brand
roles), which is round B. Launch needs A + B. Creators and staff are complete after A.

## 2. What exists

| Piece | State |
|---|---|
| `ActorResolver` (`security/`) | One interface turns a request into an `Actor`. Controllers take `Actor` as a parameter (22 classes). |
| `DenyAllActorResolver` | Every profile except `local`. Refuses everything. |
| `DevHeaderActorResolver` | `local` only. Trusts `X-Dev-*` headers. Used by every load test. |
| `ProductionAuthTest` | Proves prod and no-profile never use the dev login. |
| `Actor`, `ActorRole` | BRAND_ADMIN/MEMBER, CREATOR, OPS, OPS_LEAD, FOUNDER, SYSTEM. `requireRole`, `isOps`, `acting()`. |
| `ApiExceptionHandler`, `RequestIdFilter` | Problem responses with requestId, no internals leaked. |
| `NotificationService.toOpsOncePerDay` | Reusable for the SMS spend-cap alert. |
| Spring Security | **Not on the classpath.** No CSRF, CORS or security headers exist. |
| Users, sessions, OTP, consents, audit | **Nothing.** `jobs.creator_id` / `brand_org_id` are UUIDs with no table behind them (comments still say "Backend A id"). |

## 3. Architecture and dependencies

- New package `com.loofie.core.identity` (`api`, `app`, `domain`, `infra`), own tables, no joins into other packages and
  none from them. The only way out is `ActorResolver`. This keeps it extractable into the Identity Service the brief
  describes. ArchUnit rule added: nothing outside `identity` uses `identity.infra`.
- `users.id` becomes the id everywhere: `jobs.creator_id`, `Actor.userId`, and Backend A's `account_ref`. No migration
  of job rows is needed (production has no real data).
- Outbound: an `OtpSender` port with `SMS` and `EMAIL` channels. Adapters: a logging fake (local/test) and, once chosen,
  MSG91 and Postmark/SES. In prod with no provider configured, sending fails closed (503, clear message), the same
  pattern as the AI feedback URL.
- Secrets from the environment only: `LOOFIE_OTP_PEPPER` (HMAC key for OTP hashes), `LOOFIE_TOTP_KEY` (AES-GCM key for
  TOTP secrets). Prod refuses to start without them.

## 4. Functional gaps (what round A must add)

| # | Gap | Flow |
|---|---|---|
| G1 | Accounts: `users` with account type, status, verified phone/email, onboarding flag | 1-4 |
| G2 | One OTP flow for signup and login (send, verify) on phone and email | 3, 8 |
| G3 | Sessions in an HTTP-only cookie; logout; log out everywhere | 7 |
| G4 | Real `ActorResolver`: session → user → status → `Actor` | 7 |
| G5 | Consents (Terms, 18+, marketing) with notice version; withdraw marketing | 1, 4, 9 |
| G6 | Staff: founder bootstrap, invites, TOTP + recovery codes, peer reset, deactivate | 6 |
| G7 | Suspension with reason, logout everywhere, ops alert with the creator's active jobs | 9 |
| G8 | Phone/email change with OTP to old + new; lost-old handled by ops | Q8 |
| G9 | `audit_log` of staff actions, readable by FOUNDER/OPS_LEAD, kept 1 year | Q14 |

## 5. Security and abuse

| Threat | Defence |
|---|---|
| Guessing an OTP | 6 digits, 5 min, 5 wrong tries then dead; stored as HMAC-SHA256 with the server pepper (a plain hash of 6 digits is reversible in milliseconds) |
| SMS bombing / pumping | Per phone/email 3 per 15 min and 10 per India day; per IP ~100 per 15 min; +91 only; daily spend cap → refuse + ops alert |
| Finding out who has an account | One send/verify flow; `send` always answers the same; account type is asked only after the code proves ownership |
| Stolen session cookie | `__Host-` cookie, `HttpOnly`, `Secure`, `SameSite=Lax`; only a SHA-256 of the token is stored; revocable; staff 12 h |
| CSRF (app.loofie.in and api.loofie.in are same-site, so SameSite alone does not stop it) | Double-submit CSRF token on every state-changing request; CORS allows only the app origin with credentials |
| Session fixation | A new token is issued at every login and at MFA completion |
| Staff takeover via email | Email OTP **and** TOTP; TOTP code single use (last step stored), ±1 step clock drift; recovery codes hashed, single use |
| Privilege escalation | Account type set once at signup, never from a profile edit; ADMIN never self-chosen; staff roles only by invite, FOUNDER/OPS_LEAD only by FOUNDER |
| Suspended user keeps acting | Status checked on every request; suspension revokes all sessions in the same transaction |
| Brute force on TOTP | Counts as a failed login attempt; 5 per 15 min per staff account |
| Leaky errors | Existing problem responses; no "user not found" vs "wrong code" difference |

## 6. Performance

The login check runs on **every** request, so it sits on the engine's hot path.

- Per request: one primary-key lookup of `sessions` by token hash joined to `users` (both indexed). Target: auth adds
  **≤ 5 ms p95** at the existing 500 rps engine load.
- `last_seen_at` is written at most once an hour per session, not per request (no write amplification).
- OTP send/verify: target **p95 ≤ 300 ms at 50 rps** with the fake sender (real SMS latency is the provider's, sent
  after commit, not inside the request's DB transaction).
- No cache in round A: measured first (the calendar showed an index lookup is enough).

## 7. Scalability and reliability

- Stateless app instances: sessions and rate counters live in Postgres, so any instance answers any request.
- Rate limits use atomic upserts on a counter row per (key, window); no read-then-write race between instances.
- SMS/email are sent after the transaction commits; a provider failure marks the challenge failed and the user can
  resend (a failed send does not count towards the per-phone limit).
- Expired challenges, sessions and counters are deleted by the existing retention job.

## 8. Database (V14)

| Table | Key columns | Notes |
|---|---|---|
| `users` | id, account_type, status, phone (E.164), email (lower-case), phone_verified_at, email_verified_at, onboarding_completed, staff_role, totp_secret_enc, totp_last_step, suspended_reason | Partial unique indexes on phone / email where not DELETED |
| `consents` | user_id, purpose, notice_version, granted_at, withdrawn_at | Append-only history |
| `otp_challenges` | id, channel, destination, purpose, code_hmac, attempts, expires_at, consumed_at, ip | Index (destination, created_at) |
| `sessions` | id, token_hash, user_id, kind (FULL / MFA_PENDING), created_at, expires_at, last_seen_at, revoked_at, ip, user_agent | Unique token_hash |
| `rate_counters` | key, window_start, count | One upsert per check |
| `staff_invites` | id, email, staff_role, token_hash, invited_by, expires_at, accepted_at | 7 days, single use |
| `recovery_codes` | user_id, code_hash, used_at | 10 per enrolment |
| `audit_log` | id, at, actor_id, actor_role, action, target_user_id, reason, details jsonb, request_id | Append-only, 1 year |

## 9. Failure scenarios

| Scenario | Expected |
|---|---|
| SMS provider down | Send answers 503 "try again"; challenge failed; limit not used up |
| Two verifies of one code at once | Row lock on the challenge; one wins, the other sees "used" |
| Two signups of one phone at once | Unique index; the loser logs into the winner's account |
| DB down mid-login | 503 from the existing handler; no half-made account (one transaction) |
| Clock drift on a staff phone | ±30 s accepted; beyond that, recovery code |
| Founder loses their phone and codes | Sealed break-glass procedure (outside the app, documented) |
| Suspension while a request is running | That request finishes; the next one is refused |
| Spend cap reached | Every SMS send refused until the next India day; ops told once |

## 10. Test coverage (to add)

Unit: OTP hashing and expiry, TOTP against the RFC 6238 vectors, phone normalisation, limit windows.
Integration (real Postgres, like today): every flow on the page; enumeration (same answer for known and unknown);
CSRF refused without a token; suspended user refused on the next request; concurrent verify; concurrent signup;
spend cap; prod refuses to start without the pepper/key; dev headers still `local` only; ArchUnit boundary.
Load: engine at 500 rps with real sessions instead of dev headers; OTP send/verify at 50 rps.

## 11. Improvement plan (approved 2026-11-18)

| # | Item | Size |
|---|---|---|
| P1 | V14 schema (section 8) | M |
| P2 | OTP send/verify: limits, +91, pepper, spend cap + ops alert, `OtpSender` with a logging fake, fail closed in prod | L |
| P3 | Sessions + `SessionActorResolver` replacing `DenyAllActorResolver`; cookie, CSRF, CORS, security headers; logout, log out everywhere; `/auth/me` | L |
| P4 | Signup on first verify: account type, Terms + 18+ required, marketing optional; consent withdraw | M |
| P5 | Staff: founder bootstrap from an env var (only when no founder exists), invites, TOTP + recovery codes, peer reset, deactivate | L |
| P6 | Suspend / unsuspend with reason; revoke sessions; ops alert listing the creator's active jobs; email notice once a provider exists (inbox + log until then) | M |
| P7 | Phone/email change with OTP to old + new; ops-verified change when the old one is lost | M |
| P8 | `audit_log` written by P5-P7 and readable by FOUNDER/OPS_LEAD | S |
| P9 | Tests, load runs, this doc's results | L |

Decisions needed:

- **D1 Spring Security or own filters.** Recommended: add Spring Security for CSRF, CORS and security headers (tested
  code), with our own session filter feeding the same `Actor`. Own filters mean writing and testing CSRF ourselves.
- **D2 Session expiry.** Recommended: users 30 days after last use (renewed at most daily), staff 12 h fixed.
- **D3 Phone/email change (P7) in round A** or moved to round C. Recommended: in A, it is part of identity.

Blocked (built against the fake sender, listed until unblocked):

| Item | Who |
|---|---|
| Real SMS (MSG91 + DLT registration) | Unassigned |
| Real email (Postmark or SES, SPF/DKIM/DMARC) | Unassigned |
| `LOOFIE_OTP_PEPPER`, `LOOFIE_TOTP_KEY` in hosting secrets | Hosting |
| Brand roles in practice | Round B (organisations) |

## 12-13. What was built (2026-11-18)

| # | Item | Where |
|---|---|---|
| P1 | V14: `users`, `consents`, `otp_challenges`, `sessions`, `rate_counters`, `staff_invites`, `recovery_codes`, `audit_log`; partial index for a creator's open jobs | `V14__f1_identity_core.sql` |
| P2 | OTP send / check: HMAC with a server key, 5 min, 5 tries, +91 only. Limits are kept in Postgres: refused hits are not counted and failed sends are given back. A daily SMS cap pauses texts and sends ops a once-a-day `SMS_DAILY_CAP_REACHED` notice. `MessageSender` has a logging fake (`sender: log`) and a closed default (`sender: none` → 503). | `identity/app/OtpService`, `RateLimits`, `identity/infra/MessageSenders` |
| P3 | Sessions: the cookie holds a 256-bit token and only its SHA-256 is stored; `__Host-` cookie, HttpOnly, SameSite=Lax. Users get 30 days after last use (never more than 90); staff get 12 h. Expiry is written at most once an hour per session. `SessionActorResolver` replaced `DenyAllActorResolver`. Dev headers stay `local`-only and fall back to the cookie. Spring Security does three things: CSRF (an HMAC of the session in the `X-CSRF-Token` header, required only when a live session cookie is sent), CORS (configured origins only, with credentials) and headers (CSP, no-referrer, nosniff, frame deny, no-store). | `identity/app/SessionService`, `SessionAuthenticator`, `security/WebSecurityConfig`, `SessionActorResolver` |
| P4 | One send → verify flow. A new phone or email gets `SIGNUP_DETAILS_REQUIRED`, then signup: the type must match the channel (phone = creator, email = brand); ADMIN is refused; Terms and 18+ are required, marketing is optional. Two signups of one number at once end in one account. Consents are stored with the notice version; marketing can be withdrawn. | `AuthService`, `AccountService`, `AuthController`, `AccountController` |
| P5 | Founder bootstrap from `LOOFIE_BOOTSTRAP_FOUNDER_EMAIL`: only when no founder ever existed, under an advisory lock. Invites: FOUNDER invites any role, OPS_LEAD invites OPS; 7 days, single use, bound to the invited email; the link is also returned to the inviter. Authenticator: RFC 6238, secret encrypted with AES-GCM, ±30 s, each code works once. 10 recovery codes (HMAC, single use). A reset by a senior ends all sessions and forces setup again. | `StaffService`, `StaffRules`, `AuthService` |
| P6 | Suspend / unsuspend. Rules: reason of 3-500 characters; staff only by a FOUNDER; never yourself; never the last founder. Effects: the status changes and all sessions end in the same transaction; ops gets an `ACCOUNT_SUSPENDED` inbox notice with the creator's open job ids; after commit the person gets a message (email if they have one, else SMS). | `AccountAdminService` |
| P7 | Contact change by the person: codes go to the old and new contact, every device is logged out, and this one gets a fresh session. By ops after a manual check: the new contact is unverified until the first login, sessions end, and the change is audited. | `AccountService`, `AccountAdminService` |
| P8 | `audit_log` records: staff actions on people, staff joining, authenticator setup and reset, recovery code use, contact changes, and account views. FOUNDER and OPS_LEAD can read it; retention keeps it 365 days. | `AuditLog`, `RetentionJob` |
| — | Retention also clears spent OTPs (after 1 day), ended sessions (7 days), rate counters (2 days) and unused invites (30 days after expiry). Consents are never deleted by retention. | `RetentionJob` |

D2 as built: the idle expiry is renewed at most once an hour (not daily), so a session is never cut short by up to a day.

### API (all under `/api/core`)

| Call | Who | Answer |
|---|---|---|
| `POST /auth/otp/send` `{channel: PHONE or EMAIL, destination}` | anyone | 202 `{challengeId, expiresAt, expiresInSeconds}`; 429 + Retry-After; 503 when sending is unavailable |
| `POST /auth/otp/verify` `{challengeId, code}` | anyone | 200 `{next, user, csrfToken, ...}`; next = DONE, SIGNUP_DETAILS_REQUIRED, MFA_REQUIRED or MFA_ENROL_REQUIRED; 400 `invalid-code` (with attemptsLeft) or `code-expired`; 403 `account-suspended` |
| `POST /auth/signup` `{challengeId, accountType, acceptTerms, confirmAdult, marketing}` | after verify | 200 DONE + cookie; 422 |
| `POST /auth/staff/accept` `{challengeId, inviteToken, acceptTerms, confirmAdult}` | invited staff | 200 MFA_ENROL_REQUIRED + `totpSecret`, `otpauthUri` |
| `POST /auth/mfa/enrol` `{code}`; `POST /auth/mfa/verify` `{code}` or `{recoveryCode}` | staff mid-login | 200 DONE (+ `recoveryCodes` once, after enrol) |
| `GET /auth/me`; `POST /auth/logout`; `POST /auth/logout-all` | logged in | `me` returns the CSRF token after a page reload |
| `GET /account/consents`; `PUT /account/consents/marketing` `{granted}` | logged in | consent history |
| `POST /account/contact-change` `{newContact}`, then `POST /account/contact-change/confirm` | logged in | both codes; fresh cookie |
| `POST /ops/staff/invites` `{email, role}`; `POST /ops/staff/{id}/authenticator-reset` `{reason}` | FOUNDER / OPS_LEAD | 201 with `link` / 204 |
| `GET /ops/accounts/{id}`; `POST /ops/accounts/{id}/suspend`, `/unsuspend`, `/contact` | ops | masked summary |
| `GET /ops/audit-log?targetUserId=&before=&limit=` | FOUNDER / OPS_LEAD | newest first |

Every state-changing call made with a session cookie needs `X-CSRF-Token`.

## 14. Validation

**Tests: 281 run, 0 failures, 2 skipped (the existing Backend A contract test, which needs the real service).** New:

| Test | Proves |
|---|---|
| `TotpAndContactsTest` (7) | Authenticator: RFC 6238 test vectors, ±1 step only, base32 / otpauth URI. Contacts: Indian number formats, +91 only, email rules, masking |
| `IdentityFlowTest` (28, HTTP + Postgres) | See the list after this table |
| `IdentitySafetyTest` (4) | 8 verifies of one code at once → exactly 1 login; 2 signups of one number at once → 1 account; prod refuses to start without keys, with dev keys or with short keys; AES-GCM round trip, wrong key refused; retention clears spent data and keeps the 200-day audit and consents |
| `ProductionAuthTest` (3, rewritten) | prod and no-profile use the session resolver only; dev headers never trusted outside `local` |
| `ArchitectureTest` (+1 rule) | nothing outside identity touches identity repositories |

`IdentityFlowTest` covers:

- **Creator signup and login:** signup, then a real endpoint used with the cookie only; logging in to an existing account; a code works only once.
- **Codes and limits:**
  - `send` answers the same for known and unknown numbers.
  - 5 wrong codes kill a code; codes expire after 5 minutes.
  - 3 codes per 15 minutes, then 429 + Retry-After; the next window works.
  - Failed sends do not use up the allowance.
  - The SMS cap pauses texts and tells ops once; email is not affected.
- **Signup rules:** type must match channel, ADMIN refused, Terms and 18+ required, an unverified code refused.
- **Brands:** a brand signs up but cannot act before round B; marketing consent can be withdrawn.
- **Sessions and browser security:**
  - CSRF missing or forged → 403; logout and log out everywhere.
  - 30-day sliding expiry with a 90-day ceiling; garbage and stale cookies.
  - Security headers; CORS allows only the app origin.
- **Suspension:**
  - Sessions end at once, and login is refused after the code.
  - Ops gets a notice, the person gets an SMS with the reason, and the action is audited; unsuspend works.
  - Who may suspend whom.
- **Staff:**
  - Invite → authenticator → an ops call with the cookie only; the account view is audited; the secret is stored encrypted.
  - The second factor is required, a pending session cannot act, and a code cannot be replayed.
  - Recovery codes work once each; authenticator tries are limited.
  - A senior's reset forces setup again; an OPS_LEAD cannot reset another OPS_LEAD; nobody resets themselves.
  - Invite rules; an invite is bound to its email and works once; staff sessions last 12 h.
- **Contact changes:**
  - A phone change needs both codes, logs out every device and frees the old number.
  - A number that belongs to someone else is refused.
  - Ops changes a lost number, which is verified at the first login.

**Load (laptop, `core_test`, 100,000 creators with live sessions, warm-up discarded):**

| Phase | Rate | n | p50 | p95 | p99 | Result |
|---|---|---|---|---|---|---|
| Creator inbox, dev headers (no login work) | 500 rps | 15,000 | 0 ms | 1 ms | 2 ms | all 200 |
| Same, session cookie | 500 rps | 15,000 | 0 ms | 1 ms | 2 ms | all 200 |
| **Login cost per request (p95 difference)** | | | | **0 ms** (below the tool's 1 ms resolution; gate ≤ 5 ms) | | PASS |
| `GET /auth/me` | 500 rps | 15,000 | 0 ms | 1 ms | 1 ms | all 200 |
| OTP send | 50 rps | 1,500 | 3 ms | 7 ms | 8 ms | all 202 |
| OTP verify | 50 rps | 1,500 | 2 ms | 5 ms | 6 ms | all 200 |
| Signup | 50 rps | 1,500 | 3 ms | 6 ms | 7 ms | all 200 |

With 100k sessions, the session lookup uses two index scans (`sessions_token_hash_key`, `users_pkey`), 8 buffer hits
and 0.055 ms. The load run raised only the per-IP OTP limits, because all load came from one laptop IP; per-phone
limits and everything else ran as configured. Real SMS latency is the provider's and happens outside the request
transaction.

### Definition of Done

| Item | State |
|---|---|
| Creators: phone OTP signup / login, sessions, logout | **Done, tested, measured** |
| Staff: invite, email OTP + authenticator, recovery, reset | **Done, tested** |
| Brands: email OTP signup / login | Done; **acting on anything waits for round B** (organisations, BRAND_ADMIN / MEMBER) |
| Production no longer refuses every request | **Done** (`SessionActorResolver`) |
| Abuse limits, SMS cap, enumeration, CSRF, CORS, headers, replay | **Done, tested** |
| Suspension, consents, audit log, retention | **Done, tested** |
| Real SMS / email delivery | **Blocked: providers not chosen** (sending answers 503 until then; nothing pretends to send) |
| Production performance, alerts | **Not verified: no hosting yet** (laptop numbers only) |

## 15. Known limits and what unblocks them

| Limit | Effect | Unblocked by |
|---|---|---|
| No SMS / email provider | Real people cannot receive codes; prod answers 503 on send | Owners for MSG91 + DLT registration and Postmark / SES + SPF/DKIM/DMARC; then one adapter each behind `MessageSender` |
| Secrets | Prod will not start without `LOOFIE_OTP_PEPPER`, `LOOFIE_TOTP_KEY`, `LOOFIE_APP_ORIGINS` | Hosting secrets |
| Client IP | `getRemoteAddr()`; behind a load balancer every request would look like one IP and share the per-IP limit | Hosting: trusted proxy setting (`server.forward-headers-strategy`) once the proxy is known |
| Anyone can use up a stranger's 3 codes per 15 min | The real owner waits up to 15 minutes | Accepted (the per-IP limit slows it). Revisit if seen in the logs |
| Fixed rate windows | Up to twice a limit in a few seconds at a window edge | Accepted |
| Brands cannot act yet | Brand users get 403 on brand actions | Round B |
| Suspended creators are not yet kept out of new invitations | They cannot log in, but matching does not exist yet | F3 / F4 must check account status |
| Account deletion and anonymisation | Not built | Round C (agreed) |
| Founder's own lost authenticator with no other founder | Needs the sealed break-glass procedure | Founder: write it and store it outside the app |
| Notice version is `2026-11-draft` | Consents name a draft | Lawyer-reviewed terms and privacy notice; then change `loofie.identity.notice-version` |
| The suspension SMS is a second text template | Needs its own DLT template | Register it together with the OTP template |
