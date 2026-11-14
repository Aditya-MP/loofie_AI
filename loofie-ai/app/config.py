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
    ai_api_key: str = ""  # empty = the API refuses every call


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
