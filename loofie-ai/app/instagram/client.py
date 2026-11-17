"""Talks to Meta's Instagram API. Nothing else in the code calls Instagram.

Two logins, same methods (config/instagram.toml `flavor`):
- "facebook": the creator logs in with Facebook; her Instagram is found through the Facebook Page it
  is linked to; data comes from graph.facebook.com using her user token. The only login Meta offers
  to apps created after 2025.
- "instagram": Instagram Login on graph.instagram.com, for apps that still have it.

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


class NoLinkedInstagram(InstagramError):
    """Facebook flavor: she logged in, but no Page she manages has an Instagram account linked."""


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
    def __init__(
        self,
        app_id: str,
        app_secret: str,
        redirect_uri: str,
        http: httpx.Client | None = None,
        flavor: str | None = None,
        config_id: str = "",
    ):
        self.cfg = instagram_config()
        self.app_id, self.app_secret, self.redirect_uri = app_id, app_secret, redirect_uri
        self.flavor = flavor or self.cfg.get("flavor", "instagram")
        if self.flavor not in {"facebook", "instagram"}:
            raise ValueError(f"flavor must be 'facebook' or 'instagram', not {self.flavor!r}")
        self.config_id = config_id
        self.http = http or httpx.Client(timeout=self.cfg["timeout_seconds"])
        host = self.cfg["fb_graph_url"] if self.facebook else self.cfg["graph_url"]
        self.graph = f"{host}/{self.cfg['graph_version']}"

    @property
    def facebook(self) -> bool:
        return self.flavor == "facebook"

    # ---------------------------------------------------------------- login

    def authorize_url(self, state: str) -> str:
        if self.facebook:
            q = {
                "client_id": self.app_id,
                "redirect_uri": self.redirect_uri,
                "response_type": "code",
                "state": state,
            }
            if self.config_id:  # Facebook Login for Business: the configuration holds the permissions
                q |= {"config_id": self.config_id, "override_default_response_type": "true"}
            else:
                q["scope"] = ",".join(self.cfg["fb_scopes"])
            return f"{self.cfg['fb_authorize_url']}/{self.cfg['graph_version']}/dialog/oauth?{urlencode(q)}"
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
        if self.facebook:
            d = self._call(
                "GET",
                f"{self.graph}/oauth/access_token",
                params={
                    "client_id": self.app_id,
                    "client_secret": self.app_secret,
                    "redirect_uri": self.redirect_uri,
                    "code": code,
                },
            )
            token = d["access_token"]
            who = self._call("GET", f"{self.graph}/me", params={"fields": "id", "access_token": token})
            granted = self._call("GET", f"{self.graph}/me/permissions", params={"access_token": token})
            perms = [x["permission"] for x in granted.get("data", []) if x.get("status") == "granted"]
            return Grant(token, str(who["id"]), perms)
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

    def _exchange(self, token: str) -> tuple[str, int]:
        d = self._call(
            "GET",
            f"{self.graph}/oauth/access_token",
            params={
                "grant_type": "fb_exchange_token",
                "client_id": self.app_id,
                "client_secret": self.app_secret,
                "fb_exchange_token": token,
            },
        )
        return d["access_token"], int(d.get("expires_in") or 60 * 86400)  # Facebook: about 60 days

    def long_lived(self, short_token: str) -> tuple[str, int]:
        if self.facebook:
            return self._exchange(short_token)
        d = self._call(
            "GET",
            f"{self.cfg['graph_url']}/access_token",
            params={"grant_type": "ig_exchange_token", "client_secret": self.app_secret, "access_token": short_token},
        )
        return d["access_token"], int(d["expires_in"])

    def refresh(self, token: str) -> tuple[str, int]:
        if self.facebook:  # Facebook has no refresh call: exchanging a still-valid token gives a new one
            return self._exchange(token)
        d = self._call(
            "GET",
            f"{self.cfg['graph_url']}/refresh_access_token",
            params={"grant_type": "ig_refresh_token", "access_token": token},
        )
        return d["access_token"], int(d["expires_in"])

    # ---------------------------------------------------------------- data

    def me(self, token: str, prefer: str | None = None) -> dict:
        """The creator's Instagram profile. Facebook flavor: found through her Pages; `prefer` is the
        Instagram id to pick when several are linked (the one already connected)."""
        if self.facebook:
            fields = ",".join(self.cfg["fb_profile_fields"])
            d = self._call(
                "GET",
                f"{self.graph}/me/accounts",
                params={
                    "fields": f"name,instagram_business_account{{id,{fields}}}",
                    "limit": 100,
                    "access_token": token,
                },
            )
            found = [p["instagram_business_account"] for p in d.get("data", []) if p.get("instagram_business_account")]
            if not found:
                pages = d.get("data", [])
                raise NoLinkedInstagram(
                    "the login returned no Facebook Pages at all (was a Page chosen on the Allow screen?)"
                    if not pages
                    else f"{len(pages)} Facebook Page(s) returned ({', '.join(str(x.get('name')) for x in pages)}), none has an Instagram account linked"
                )
            ig = next((a for a in found if a["id"] == prefer), found[0])
            # graph.facebook.com can't say creator vs business; a linked account is always professional.
            return {**ig, "user_id": ig["id"], "account_type": "PROFESSIONAL"}
        return _unwrap(
            self._call(
                "GET",
                f"{self.graph}/me",
                params={"fields": ",".join(self.cfg["profile_fields"]), "access_token": token},
            )
        )

    def media(self, token: str, limit: int | None = None, ig_user_id: str | None = None) -> list[dict]:
        limit = limit or self.cfg["media_limit"]
        who = ig_user_id if self.facebook else "me"
        out: list[dict] = []
        params = {"fields": ",".join(self.cfg["media_fields"]), "access_token": token, "limit": min(limit, 50)}
        while len(out) < limit:
            page = self._call("GET", f"{self.graph}/{who}/media", params=params)
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
