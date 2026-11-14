"""Talks to Meta's Instagram API (Instagram Login). Nothing else in the code calls Instagram.

Tokens travel in query strings (Meta's documented style), so httpx's request log is silenced:
otherwise every URL, token included, would land in the server logs.
"""

import logging
from dataclasses import dataclass
from urllib.parse import urlencode

import httpx

from app.config import load_toml

logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)

TOKEN_EXPIRED = 190  # Meta's error code for an expired / revoked token


def instagram_config() -> dict:
    return load_toml("instagram")


class InstagramError(Exception):
    def __init__(self, message: str, status: int | None = None, code: int | None = None):
        super().__init__(message)
        self.status, self.code = status, code

    @property
    def token_dead(self) -> bool:
        return self.code == TOKEN_EXPIRED or self.status == 401


@dataclass
class Grant:
    access_token: str
    user_id: str
    permissions: list[str]


def _unwrap(data: dict) -> dict:
    # Some Instagram Login responses come wrapped as {"data": [{...}]}, others flat.
    if isinstance(data.get("data"), list) and data["data"] and "paging" not in data:
        return data["data"][0]
    return data


class InstagramClient:
    def __init__(self, app_id: str, app_secret: str, redirect_uri: str, http: httpx.Client | None = None):
        self.cfg = instagram_config()
        self.app_id, self.app_secret, self.redirect_uri = app_id, app_secret, redirect_uri
        self.http = http or httpx.Client(timeout=self.cfg["timeout_seconds"])
        self.graph = f"{self.cfg['graph_url']}/{self.cfg['graph_version']}"

    # ---------------------------------------------------------------- login

    def authorize_url(self, state: str) -> str:
        q = {
            "client_id": self.app_id,
            "redirect_uri": self.redirect_uri,
            "response_type": "code",
            "scope": ",".join(self.cfg["scopes"]),
            "state": state,
            "enable_fb_login": "false",  # creators log in with Instagram, not Facebook
        }
        return f"{self.cfg['authorize_url']}?{urlencode(q)}"

    def exchange_code(self, code: str) -> Grant:
        code = code.removesuffix("#_")  # Instagram appends "#_" to the redirect; not part of the code
        data = self._call(
            "POST",
            self.cfg["token_url"],
            data={
                "client_id": self.app_id,
                "client_secret": self.app_secret,
                "grant_type": "authorization_code",
                "redirect_uri": self.redirect_uri,
                "code": code,
            },
        )
        d = _unwrap(data)
        perms = d.get("permissions") or []
        if isinstance(perms, str):
            perms = [p.strip() for p in perms.split(",") if p.strip()]
        return Grant(d["access_token"], str(d["user_id"]), perms)

    def long_lived(self, short_token: str) -> tuple[str, int]:
        d = self._call(
            "GET",
            f"{self.cfg['graph_url']}/access_token",
            params={"grant_type": "ig_exchange_token", "client_secret": self.app_secret, "access_token": short_token},
        )
        return d["access_token"], int(d["expires_in"])

    def refresh(self, token: str) -> tuple[str, int]:
        d = self._call(
            "GET",
            f"{self.cfg['graph_url']}/refresh_access_token",
            params={"grant_type": "ig_refresh_token", "access_token": token},
        )
        return d["access_token"], int(d["expires_in"])

    # ---------------------------------------------------------------- data

    def me(self, token: str) -> dict:
        return _unwrap(
            self._call(
                "GET",
                f"{self.graph}/me",
                params={"fields": ",".join(self.cfg["profile_fields"]), "access_token": token},
            )
        )

    def media(self, token: str, limit: int | None = None) -> list[dict]:
        limit = limit or self.cfg["media_limit"]
        out: list[dict] = []
        params = {"fields": ",".join(self.cfg["media_fields"]), "access_token": token, "limit": min(limit, 50)}
        while len(out) < limit:
            page = self._call("GET", f"{self.graph}/me/media", params=params)
            out.extend(page.get("data", []))
            after = (page.get("paging") or {}).get("cursors", {}).get("after")
            if not page.get("data") or not after:
                break
            params = {**params, "after": after}
        return out[:limit]

    def insights(self, token: str, media_id: str) -> dict[str, int]:
        """{"views": n, "reach": n}. A post with no insights (albums, too new, missing permission)
        returns {} instead of failing the whole sync; only a dead token is raised."""
        try:
            d = self._call(
                "GET",
                f"{self.graph}/{media_id}/insights",
                params={"metric": ",".join(self.cfg["insight_metrics"]), "access_token": token},
            )
        except InstagramError as e:
            if e.token_dead:
                raise
            return {}
        out = {}
        for m in d.get("data", []):
            values = m.get("values") or []
            if values and isinstance(values[0].get("value"), int):
                out[m["name"]] = values[0]["value"]
        return out

    # ---------------------------------------------------------------- plumbing

    def _call(self, method: str, url: str, **kw) -> dict:
        try:
            r = self.http.request(method, url, **kw)
        except httpx.HTTPError as e:
            raise InstagramError(f"Instagram unreachable: {type(e).__name__}") from e
        try:
            data = r.json()
        except ValueError:
            data = {}
        if r.status_code >= 400 or "error" in data or "error_type" in data:
            err = data.get("error") if isinstance(data.get("error"), dict) else data
            message = err.get("message") or err.get("error_message") or f"HTTP {r.status_code}"
            raise InstagramError(message, status=r.status_code, code=err.get("code"))
        return data
