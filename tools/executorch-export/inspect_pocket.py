"""Load upstream Pocket TTS and print its module structure.

Sanity check that the export environment can load the PyTorch model
end-to-end before we sink time into writing torch.export wrappers.

Run:
    uv run python inspect_pocket.py
"""

import logging
import sys

import torch
from pocket_tts.models.tts_model import TTSModel


def main() -> int:
    logging.basicConfig(level=logging.INFO)

    print("Loading Pocket TTS english_2026-04 model from upstream config + HF weights…")
    print("(downloads safetensors on first run; ~280 MB)")
    model = TTSModel.load_model(language="english_2026-04")
    model.eval()

    print()
    print("=" * 72)
    print("Top-level structure")
    print("=" * 72)
    for name, child in model.named_children():
        n_params = sum(p.numel() for p in child.parameters())
        size_mb = sum(p.numel() * p.element_size() for p in child.parameters()) / 1e6
        print(f"  {name:20s}  {type(child).__name__:30s}  {n_params/1e6:6.1f} M params  {size_mb:6.1f} MB")

    total_params = sum(p.numel() for p in model.parameters())
    total_mb = sum(p.numel() * p.element_size() for p in model.parameters()) / 1e6
    print()
    print(f"  TOTAL                                              {total_params/1e6:6.1f} M params  {total_mb:6.1f} MB")

    print()
    print("=" * 72)
    print("Flow LM detail")
    print("=" * 72)
    for name, child in model.flow_lm.named_children():
        n_params = sum(p.numel() for p in child.parameters())
        print(f"  flow_lm.{name:20s}  {type(child).__name__:30s}  {n_params/1e6:6.1f} M params")

    print()
    print("=" * 72)
    print("Mimi detail")
    print("=" * 72)
    for name, child in model.mimi.named_children():
        n_params = sum(p.numel() for p in child.parameters())
        print(f"  mimi.{name:20s}  {type(child).__name__:30s}  {n_params/1e6:6.1f} M params")

    print()
    print(f"Sample rate: {model.sample_rate} Hz")
    print(f"Frame rate:  {model.config.mimi.frame_rate} Hz")
    print(f"Latent dim:  {model.config.mimi.quantizer.dimension}")
    print(f"Cond dim:    {model.flow_lm.dim}")

    # Quick smoke test: tokenize a short input.
    print()
    print("Smoke test: tokenize 'Hello world.'")
    prepared = model.flow_lm.conditioner.prepare("Hello world.")
    print(f"  tokens shape: {prepared.tokens.shape}")
    print(f"  tokens dtype: {prepared.tokens.dtype}")
    print(f"  first 10 ids: {prepared.tokens[0, :10].tolist()}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
