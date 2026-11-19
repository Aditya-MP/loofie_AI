# Holiday lists 2026 and 2027 (open ask 37)

`2026.json` and `2027.json` are ready to load with the ops import (`PUT /api/core/ops/holidays/years/{year}`).
Both were checked against the real endpoint in preview mode on 2026-11-19: no bad rows. 2026 adds 14 days and 2027
adds 13; the fixed national days already in the table come back as unchanged. Nothing was saved.

## Where the dates come from

- **Central government gazetted holidays**, DoPT office memorandum F.No. 12/2/2023-JCA: 3 July 2025 for 2026,
  16 July 2026 for 2027. The dates were read from reproductions of the memorandum (staffnews.in, and the Delhi
  government notification of 4 Dec 2025 for 2026), not from the DoPT PDF itself. **Check them against the DoPT
  annexure before applying.**
- Rows with no state (`""`) are the 14 holidays that are compulsory everywhere. Holi, Ram Navami and Janmashtami are
  the 3 that Delhi picks, so they are marked `DL`. Other states pick their own 3 (Pongal, Onam, Chhath and others)
  from a list of 12. Add those rows per state once the brands' pickup states are known.
- 2026: Bakrid moved from 27 to 28 May (Centre's notice). 2027: Independence Day and Milad-un-Nabi fall on the same
  day (15 Aug), so they are one row.
- Moon-dependent days (Id, Bakrid, Muharram, Milad-un-Nabi) can move by a day at short notice. Correct a single day
  with `PUT /api/core/ops/holidays` (one row) when that happens.

## Decision for ops before applying

These are government office holidays. **Couriers work on many of them.** A row in this table stops the brand's
working-day clocks (ship deadline, review clock when switched on) for that day. Delete any row on which couriers do
pick up, then load:

1. Preview: `PUT /api/core/ops/holidays/years/2027` with the file as the body (preview is the default). Read the
   ADDED / RENAMED / REMOVED list and the running clocks each change does not reach.
2. Apply: the same call with `?dryRun=false`. All or nothing; every change is kept in the holiday history.
3. Then set `loofie.calendar.expected-national-per-year` to the number of national rows per year (13 or 14 with
   these files; lower if rows were deleted), so a half-entered year raises `HOLIDAYS_MISSING`.

The next date the clocks will skip: **24 Nov 2026, Guru Nanak Jayanti**, so load 2026 before then. Load the 2028
list when DoPT publishes it (July 2027 if the pattern holds).
