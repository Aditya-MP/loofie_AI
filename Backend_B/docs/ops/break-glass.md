# Break-glass: a founder loses their authenticator (open ask 31) — DRAFT for the founder

Staff log in with a code plus an authenticator (TOTP), and have recovery codes (F1 round A). If the only founder
loses both the authenticator and the recovery codes, nobody can recover the account from inside the app, and no
support override exists on purpose. This procedure prevents that. It follows NIST SP 800-63B-4 section 4.2 and the
practice of Microsoft (emergency accounts) and AWS (root account MFA).

## Set up once

1. **A second account with the FOUNDER role**, held by a second trusted person, with its own authenticator. The app
   lets only a founder reset another founder's authenticator (`StaffRules.requireMayResetAuthenticator`); an ops
   lead cannot. With a single founder account there is no in-app way back, only the sealed codes.
2. **Recovery codes printed and sealed**, in two places away from each other (for example a home safe and a bank
   locker). The app shows its 10 codes once, at enrolment; store them nowhere online.
3. **The same protection for the accounts around the app**: the cloud root account (two hardware keys, one kept in
   the safe), the domain registrar, the email admin and the Git hosting admin. Losing one of these is as bad as
   losing the app login.
4. Write down, in the same sealed envelope, who to call and in what order.

## When it happens

1. The founder logs in with the email code plus one sealed recovery code (each works once). That gets them in, but
   the lost phone still holds the old authenticator.
2. The second founder checks who the person is (video call, known phone number) and resets the founder's
   authenticator (`POST /api/core/ops/staff/{id}/authenticator-reset`, with a reason). That ends every session of
   the account; at the next login the founder enrols a new authenticator and receives 10 new recovery codes, which
   go into the envelopes. Two people, two methods.
3. With no recovery code left, step 2 alone does it.
4. Every recovery-code use and reset is in the audit log. All admins are told the same day.
5. Never: recovery by a support ticket, by email alone, by security questions, or by editing the database by hand.

## Test

Every 90 days: open one envelope, check that the codes are readable and the hardware keys work, and record the date.
Replace any code used.

## Decisions for the founder

- Who holds the second FOUNDER account (invite them as staff with the founder role).
- Where the two envelopes are kept.
- Whether to buy two hardware keys (about Rs 2,500-5,000 each) for the cloud root account.
