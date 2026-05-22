#!/usr/bin/env python3
"""Print the SHA-256 + size of a Kitten engine tar.bz2 bundle.

Useful when refreshing the bundle pinned in EngineCatalog.kt:

    1. Fetch the upstream tarball (or a mirror you've uploaded):
       curl -L -o /tmp/kitten.tar.bz2 \\
           https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2

    2. Verify it:
       python3 scripts/verify-kitten-bundle.py /tmp/kitten.tar.bz2

    3. Paste the printed sha256 + size into EngineCatalog.KITTEN.archive.

This replaces the older `generate-kitten-manifest.py`, which emitted the
2158-line per-file manifest used by v0.1.0–v0.1.2. v0.1.3 downloads the
archive as a single asset, so only the archive's sha256 + size need to be
pinned in source.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "archive",
        type=Path,
        help="Path to a kitten-nano-en-v0_1-fp16.tar.bz2 archive.",
    )
    args = parser.parse_args(argv)

    if not args.archive.is_file():
        print(f"error: {args.archive} not found or not a file", file=sys.stderr)
        return 2

    size = args.archive.stat().st_size
    h = hashlib.sha256()
    with args.archive.open("rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)

    print(f"file:    {args.archive}")
    print(f"size:    {size} bytes ({size / (1024 * 1024):.2f} MiB)")
    print(f"sha256:  {h.hexdigest()}")
    print()
    print("Paste into app/src/main/java/app/marmalade/tts/install/EngineCatalog.kt:")
    print(f"    sizeBytes = {size}L,")
    print(f'    sha256 = "{h.hexdigest()}",')
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
