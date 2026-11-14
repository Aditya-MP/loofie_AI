"""Instagram Login and fetch (F1 Creator Passport).

The creator taps "Continue with Instagram", approves once, and the AI service fetches their
followers, posts and per-post views/reach from Meta's official API (no scraping). Those numbers
are stored as API_VERIFIED and re-fetched weekly, so the passport, Model 3 and the matcher always
see fresh, verified data. Only Professional (Creator/Business) accounts can log in: Meta's rule.
"""
