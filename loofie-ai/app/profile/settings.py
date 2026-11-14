from app.config import load_toml


def profile_config() -> dict:
    return load_toml("profile")
