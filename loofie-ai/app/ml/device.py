import torch

from app.config import get_settings


def get_device() -> str:
    """The device every model loads on. Fails loudly instead of silently falling back to CPU."""
    device = get_settings().device
    if device.startswith("cuda") and not torch.cuda.is_available():
        raise RuntimeError(
            "DEVICE=cuda but torch sees no CUDA GPU. Check the NVIDIA driver and that torch is a "
            "+cu build (pip install torch --index-url https://download.pytorch.org/whl/cu118)."
        )
    return device


if __name__ == "__main__":
    d = get_device()
    print(d, torch.cuda.get_device_name(0) if d.startswith("cuda") else "")
