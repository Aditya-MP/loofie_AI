"""Settings: secrets and machine settings from .env, model settings from config/*.toml."""

import tomllib
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parents[1]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ROOT / ".env", extra="ignore")

    database_url: str = "postgresql+psycopg://loofie:loofie_dev@localhost:5433/loofie_ai"
    test_database_url: str = "postgresql+psycopg://loofie:loofie_dev@localhost:5433/loofie_ai_test"
    device: str = "cuda"
    embed_model: str = "intfloat/multilingual-e5-small"
    embed_dim: int = 384
    ai_api_key: str = ""  # one shared key, caller name "default" (kept for local use)
    # Named keys, one per caller: "backend:KEY1,ops:KEY2". Rotate by adding the new key under the
    # same name ("backend:OLD,backend:NEW"), switching the caller over, then removing the old one.
    ai_api_keys: str = ""

    def api_keys(self) -> list[tuple[str, str]]:
        """[(caller, key)]. Empty = the API refuses every call."""
        keys = [("default", self.ai_api_key)] if self.ai_api_key else []
        for item in filter(None, (x.strip() for x in self.ai_api_keys.split(","))):
            name, sep, key = item.partition(":")
            if not sep or not name.strip() or not key.strip():
                raise ValueError("AI_API_KEYS must look like name:key,name:key")
            keys.append((name.strip(), key.strip()))
        return keys

    # Instagram Login (Meta developer app → Instagram → API setup with Instagram login).
    ig_app_id: str = ""  # empty = Instagram login switched off
    ig_app_secret: str = ""
    ig_redirect_uri: str = ""  # public https URL of /auth/instagram/callback, registered in the Meta app
    ig_token_key: str = ""  # Fernet key that encrypts stored Instagram tokens
    ig_flavor: str = ""  # "facebook" | "instagram"; empty = config/instagram.toml
    ig_fb_config_id: str = ""  # Facebook Login for Business configuration id (required for unified)
    ig_workers: int = 0  # sync worker threads inside the API (0 = none; use `loofie instagram worker`)
    app_return_url: str = ""  # creator app page the browser returns to after login


@lru_cache
def get_settings() -> Settings:
    return Settings()


CONFIG_DIR = ROOT / "config"
_overrides: dict[str, dict] = {}


def load_toml(name: str) -> dict:
    """config/<name>.toml: weights, thresholds and word lists. Bump `version` inside on any change."""
    if name in _overrides:
        return _overrides[name]
    return _read_toml(name)


@lru_cache
def _read_toml(name: str) -> dict:
    with (CONFIG_DIR / f"{name}.toml").open("rb") as f:
        return tomllib.load(f)


def reload_config() -> None:
    """Forget cached files, e.g. after a promote or rollback rewrote one."""
    _read_toml.cache_clear()


@contextmanager
def config_override(name: str, cfg: dict):
    """Score a candidate config without touching the live file. Process-wide: CLI use only,
    never inside the running API."""
    _overrides[name] = cfg
    try:
        yield
    finally:
        _overrides.pop(name, None)
