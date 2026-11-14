"""The three secrets-handling pieces of Instagram Login, kept small so they're easy to review.

1. `state`: the login link carries a signed, expiring note of WHICH Loofie account started it.
   Instagram sends it back unchanged; a forged or replayed-late link fails the signature/expiry.
2. Token encryption: Instagram tokens are stored encrypted (Fernet: AES + HMAC), never in plain text.
3. Meta's `signed_request`: deauthorize / data-deletion calls come from Meta signed with our app
   secret; anything that doesn't verify is refused.
"""

import base64
import hashlib
import hmac
import json
import secrets
import time

from cryptography.fernet import Fernet, InvalidToken


class SecurityError(ValueError):
    pass


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _state_key(token_key: str) -> bytes:
    # A separate key derived from the token key, so one secret in .env covers both jobs.
    return hashlib.sha256(b"loofie-instagram-state:" + token_key.encode()).digest()


def sign_state(account_ref: str, token_key: str, ttl_seconds: int, now: float | None = None) -> str:
    body = _b64(
        json.dumps(
            {"ref": account_ref, "exp": int((now or time.time()) + ttl_seconds), "n": secrets.token_hex(8)}
        ).encode()
    )
    sig = _b64(hmac.new(_state_key(token_key), body.encode(), hashlib.sha256).digest())
    return f"{body}.{sig}"


def read_state(state: str, token_key: str, now: float | None = None) -> str:
    """Returns the account_ref the login was started for, or raises SecurityError."""
    try:
        body, sig = state.split(".", 1)
    except ValueError as e:
        raise SecurityError("malformed state") from e
    expected = _b64(hmac.new(_state_key(token_key), body.encode(), hashlib.sha256).digest())
    if not hmac.compare_digest(sig, expected):
        raise SecurityError("state signature does not match")
    data = json.loads(_unb64(body))
    if data["exp"] < (now or time.time()):
        raise SecurityError("login link expired")
    return data["ref"]


def encrypt(token: str, token_key: str) -> str:
    return Fernet(token_key.encode()).encrypt(token.encode()).decode()


def decrypt(blob: str, token_key: str) -> str:
    try:
        return Fernet(token_key.encode()).decrypt(blob.encode()).decode()
    except InvalidToken as e:
        raise SecurityError("stored token can't be decrypted (wrong IG_TOKEN_KEY?)") from e


def new_token_key() -> str:
    return Fernet.generate_key().decode()


def parse_signed_request(signed_request: str, app_secret: str) -> dict:
    """Meta's format: base64url(HMAC-SHA256(payload_b64, app_secret)) + "." + payload_b64."""
    try:
        sig_b64, payload_b64 = signed_request.split(".", 1)
        sig = _unb64(sig_b64)
        payload = json.loads(_unb64(payload_b64))
    except (ValueError, json.JSONDecodeError) as e:
        raise SecurityError("malformed signed_request") from e
    expected = hmac.new(app_secret.encode(), payload_b64.encode(), hashlib.sha256).digest()
    if not hmac.compare_digest(sig, expected):
        raise SecurityError("signed_request signature does not match")
    return payload


def make_signed_request(payload: dict, app_secret: str) -> str:
    """The inverse, for tests (Meta does this on their side)."""
    payload_b64 = _b64(json.dumps(payload).encode())
    sig = hmac.new(app_secret.encode(), payload_b64.encode(), hashlib.sha256).digest()
    return f"{_b64(sig)}.{payload_b64}"
