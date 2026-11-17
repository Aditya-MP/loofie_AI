"""Talks to Meta's Instagram API. Nothing else in the code calls Instagram.

Three logins, same methods (config/instagram.toml `flavor`, or IG_FLAVOR):
- "unified": Instagram API with Unified Login. The creator logs in with Instagram (or Facebook)
  and picks the Instagram account to share. No Facebook Page needed. The token belongs to a business
  portfolio Meta makes for her; data comes from graph.facebook.com by Instagram account id. Needs a
  Facebook Login for Business configuration (IG_FB_CONFIG_ID). Meta's recommended setup for new apps.
- "facebook": Facebook login; her Instagram is found through the Facebook Page it is linked to.
- "instagram": Instagram Login on graph.instagram.com, for apps that still have it (closed to new apps).

Every Meta error is classified once here (`token_dead`, `retryable`, `throttled`), so the sync
queue can decide: give up, retry later, or slow everyone down. Meta's usage headers are read on every
call; `pause_seconds()` tells workers to back off before Meta starts refusing.

Tokens travel in query strings (Meta's documented style), so httpx's request log is silenced:
otherwise every URL, token included, would land in the server logs.
"""

import json
import logging
import threading
import time
from dataclasses import dataclass
from urllib.parse import urlencode

import httpx

from app.config import load_toml

logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)

TOKEN_EXPIRED = 190  # Meta's error code for an expired / revoked token
# Meta's "slow down" codes: app / user / page / business-use-case limits and spikes.
THROTTLE_CODES = {4, 17, 32, 613, 80001, 80002, 80008}
# Meta's "temporary problem, try again" codes.
TRANSIENT_CODES = {1, 2, 341}
FLAVORS = {"unified", "facebook", "instagram"}


def instagram_config() -> dict:
    return load_toml("instagram")


class InstagramError(Exception):
    def __init__(self, message: str, status: int | None = None, code: int | None = None):
        super().__init__(message)
        self.status, self.code = status, code

    @property
    def token_dead(self) -> bool:
        return self.code == TOKEN_EXPIRED or self.status == 401

    @property
    def throttled(self) -> bool:
        return self.code in THROTTLE_CODES or self.status == 429

    @property
    def retryable(self) -> bool:
        """Worth trying again later: Meta unreachable, Meta's own error, or a rate limit."""
        if self.token_dead:
            return False
        return self.status is None or self.status >= 500 or self.throttled or self.code in TRANSIENT_CODES


class NoLinkedInstagram(InstagramError):
    """She logged in, but no Instagram account came with it (facebook: no Page with Instagram linked;
    unified: no Instagram account shared on Meta's screen)."""


class SeveralInstagramAccounts(InstagramError):
    """She shared more than one Instagram account: we refuse rather than guess which one is hers."""


@dataclass
class Grant:
    access_token: str
    user_id: str
    permissions: list[str]
    business_id: str | None = None  # unified: the business portfolio the token belongs to
    expires_in: int | None = None  # seconds, from the token response; None = Meta gave no expiry


def _unwrap(data: dict) -> dict:
    # Some Instagram Login responses come wrapped as {"data": [{...}]}, others flat.
    if isinstance(data.get("data"), list) and data["data"] and "paging" not in data:
        return data["data"][0]
    return data


def _usage_percent(headers: httpx.Headers) -> tuple[float, int]:
    """(highest usage %, minutes until access is back) from Meta's usage headers. (0, 0) if absent."""
    worst, regain = 0.0, 0
    for name in ("x-app-usage", "x-ad-account-usage"):
        try:
            d = json.loads(headers.get(name) or "{}")
            worst = max([worst, *(float(v) for v in d.values() if isinstance(v, int | float))])
        except (ValueError, TypeError):
            pass
    try:
        buc = json.loads(headers.get("x-business-use-case-usage") or "{}")
        for entries in buc.values():
            for e in entries if isinstance(entries, list) else []:
                for k in ("call_count", "total_cputime", "total_time"):
                    worst = max(worst, float(e.get(k) or 0))
                regain = max(regain, int(e.get("estimated_time_to_regain_access") or 0))
    except (ValueError, TypeError, AttributeError):
        pass
    return worst, regain


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
        if self.flavor not in FLAVORS:
            raise ValueError(f"flavor must be one of {sorted(FLAVORS)}, not {self.flavor!r}")
        if self.flavor == "unified" and not config_id:
            raise ValueError("unified login needs a Facebook Login for Business configuration id (IG_FB_CONFIG_ID)")
        self.config_id = config_id
        self.http = http or httpx.Client(timeout=self.cfg["timeout_seconds"])
        host = self.cfg["fb_graph_url"] if self.on_facebook_graph else self.cfg["graph_url"]
        self.graph = f"{host}/{self.cfg['graph_version']}"
        # Shared by every worker thread: when Meta says "slow down", everyone slows down.
        self._lock = threading.Lock()
        self._paused_until = 0.0
        self.last_usage = 0.0

    @property
    def facebook(self) -> bool:
        return self.flavor == "facebook"

    @property
    def unified(self) -> bool:
        return self.flavor == "unified"

    @property
    def on_facebook_graph(self) -> bool:
        return self.flavor in {"facebook", "unified"}

    @property
    def can_refresh(self) -> bool:
        """Unified tokens can't be renewed by us; they live as long as the configuration says."""
        return not self.unified

    # ---------------------------------------------------------------- rate limits

    def pause_seconds(self) -> float:
        """How long workers should wait before the next Meta call (0 = go)."""
        with self._lock:
            return max(0.0, self._paused_until - time.time())

    def _note_usage(self, headers: httpx.Headers) -> None:
        q = self.cfg.get("queue", {})
        worst, regain = _usage_percent(headers)
        with self._lock:
            self.last_usage = worst
            if regain:
                self._paused_until = max(self._paused_until, time.time() + 60 * regain)
            elif worst >= q.get("usage_pause_percent", 85):
                self._paused_until = max(self._paused_until, time.time() + q.get("usage_pause_seconds", 60))

    def _note_throttle(self) -> None:
        with self._lock:
            pause = self.cfg.get("queue", {}).get("throttle_pause_seconds", 300)
            self._paused_until = max(self._paused_until, time.time() + pause)

    # ---------------------------------------------------------------- login

    def authorize_url(self, state: str) -> str:
        if self.on_facebook_graph:
            q = {
                "client_id": self.app_id,
                "redirect_uri": self.redirect_uri,
                "response_type": "code",
                "state": state,
            }
            if self.config_id:  # Facebook Login for Business: the configuration holds the permissions
                q["config_id"] = self.config_id
                if self.facebook:
                    q["override_default_response_type"] = "true"
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
        if self.on_facebook_graph:
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
            fields = "id,client_business_id" if self.unified else "id"
            who = self._call("GET", f"{self.graph}/me", params={"fields": fields, "access_token": token})
            granted = self._call("GET", f"{self.graph}/me/permissions", params={"access_token": token})
            perms = [x["permission"] for x in granted.get("data", []) if x.get("status") == "granted"]
            expires = int(d["expires_in"]) if d.get("expires_in") else None
            return Grant(token, str(who["id"]), perms, who.get("client_business_id"), expires)
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

    def long_lived(self, grant: Grant) -> tuple[str, int | None]:
        """(token, seconds until it expires; None = doesn't expire)."""
        if self.unified:  # the configuration decides the lifetime; nothing to exchange
            return grant.access_token, grant.expires_in
        if self.facebook:
            return self._exchange(grant.access_token)
        d = self._call(
            "GET",
            f"{self.cfg['graph_url']}/access_token",
            params={
                "grant_type": "ig_exchange_token",
                "client_secret": self.app_secret,
                "access_token": grant.access_token,
            },
        )
        return d["access_token"], int(d["expires_in"])

    def refresh(self, token: str) -> tuple[str, int]:
        if not self.can_refresh:
            raise InstagramError("unified tokens can't be renewed by the app; the creator reconnects", status=400)
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
        """The creator's Instagram profile, with `user_id` = Instagram account id. `prefer` is the
        account already connected (a re-sync keeps reading that one)."""
        if self.unified:
            return self._unified_me(token, prefer)
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
                names = ", ".join(str(x.get("name")) for x in pages)
                raise NoLinkedInstagram(
                    "the login returned no Facebook Pages at all (was a Page chosen on the Allow screen?)"
                    if not pages
                    else f"{len(pages)} Facebook Page(s) returned ({names}), none has an Instagram account linked"
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

    def _unified_me(self, token: str, prefer: str | None) -> dict:
        accounts, params = [], {"fields": "id,username", "limit": 100, "access_token": token}
        while True:  # Meta pages long lists
            page = self._call("GET", f"{self.graph}/me/assigned_instagram_accounts", params=params)
            accounts += page.get("data", [])
            after = (page.get("paging") or {}).get("cursors", {}).get("after")
            if not page.get("data") or not after or not (page.get("paging") or {}).get("next"):
                break
            params = {**params, "after": after}
        if not accounts:
            raise NoLinkedInstagram("no Instagram account was shared on Meta's screen")
        chosen = next((a for a in accounts if str(a["id"]) == str(prefer)), None) if prefer else None
        if chosen is None:
            if len(accounts) > 1:
                handles = ", ".join("@" + str(a.get("username")) for a in accounts)
                raise SeveralInstagramAccounts(
                    f"{len(accounts)} Instagram accounts shared ({handles}); share only yours"
                )
            chosen = accounts[0]
        ig_id = str(chosen["id"])
        profile = self._call(
            "GET",
            f"{self.graph}/{ig_id}",
            params={"fields": ",".join(self.cfg["fb_profile_fields"]), "access_token": token},
        )
        # Unified Login only serves professional accounts (Meta's rule), so no account_type field.
        return {**profile, "id": ig_id, "user_id": ig_id, "account_type": "PROFESSIONAL"}

    def media(self, token: str, limit: int | None = None, ig_user_id: str | None = None) -> list[dict]:
        limit = limit or self.cfg["media_limit"]
        if self.on_facebook_graph and not ig_user_id:
            raise ValueError("graph.facebook.com needs the Instagram account id to read media")
        who = ig_user_id if self.on_facebook_graph else "me"
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
        returns {} instead of failing the whole sync. A dead token or a rate limit is raised, so the
        sync is retried later instead of saving numbers with the views missing."""
        try:
            d = self._call(
                "GET",
                f"{self.graph}/{media_id}/insights",
                params={"metric": ",".join(self.cfg["insight_metrics"]), "access_token": token},
            )
        except InstagramError as e:
            if e.token_dead or e.retryable:
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
        self._note_usage(r.headers)
        try:
            data = r.json()
        except ValueError:
            data = {}
        if r.status_code >= 400 or "error" in data or "error_type" in data:
            err = data.get("error") if isinstance(data.get("error"), dict) else data
            message = err.get("message") or err.get("error_message") or f"HTTP {r.status_code}"
            e = InstagramError(message, status=r.status_code, code=err.get("code"))
            if e.throttled:
                self._note_throttle()
            raise e
        return data
