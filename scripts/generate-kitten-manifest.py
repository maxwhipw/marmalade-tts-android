#!/usr/bin/env python3
"""Generate KittenEspeakDataManifest.kt from a locally extracted bundle.

The Kitten engine pulls its phonemizer data from espeak-ng-data, which
ships as ~355 small files. We don't want to commit a hand-maintained
355-entry list. Instead this script walks a locally extracted bundle
(downloaded once from the Sherpa-ONNX HF mirror) and emits the Kotlin
source file the installer reads at runtime.

Usage:
    # 1. Fetch and extract the bundle once:
    curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2 \\
        -o /tmp/kitten.tar.bz2
    mkdir -p /tmp/kitten && tar -xjf /tmp/kitten.tar.bz2 -C /tmp/kitten

    # 2. Regenerate the manifest:
    python3 scripts/generate-kitten-manifest.py \\
        --bundle-root /tmp/kitten/kitten-nano-en-v0_1-fp16 \\
        > app/src/main/java/app/marmalade/tts/install/KittenEspeakDataManifest.kt

The catalog itself (`EngineCatalog.kt`) is not regenerated — it points
at this manifest plus the top-level model files, which are hand-curated.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import sys
from pathlib import Path


HF_BASE = (
    "https://huggingface.co/csukuangfj/"
    "sherpa-onnx-kitten-nano-en-v0_1-fp16/resolve/main"
)

KOTLIN_HEADER = '''\
package app.marmalade.tts.install

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EngineCatalog.KITTEN.files  ◄── concatenates KITTEN_TOP_LEVEL with FILES
//                                       │
//                                       ▼
//                                EngineInstaller.install("kitten")
//                                       │
//                                       └── for each file: GET → verify → write
// -----------------------------------------------------------------------------

/**
 * Static manifest of the espeak-ng phonemizer data files that the Kitten
 * engine needs at runtime.
 *
 * **AUTO-GENERATED** by `scripts/generate-kitten-manifest.py` — do not edit
 * by hand. Re-run the script when you bump the upstream Kitten bundle.
 */
internal object KittenEspeakDataManifest {

    private const val BASE: String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-kitten-nano-en-v0_1-fp16/resolve/main"

    val FILES: List<EngineFile> = listOf(
'''

KOTLIN_FOOTER = '''\
    )
}
'''


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(64 * 1024):
            h.update(chunk)
    return h.hexdigest()


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--bundle-root",
        required=True,
        type=Path,
        help=(
            "Path to the extracted kitten-nano-en-v0_1-fp16/ directory "
            "(must contain an espeak-ng-data/ subdirectory)."
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Write Kotlin source here (defaults to stdout).",
    )
    args = parser.parse_args(argv)

    data_root: Path = args.bundle_root / "espeak-ng-data"
    if not data_root.is_dir():
        print(
            f"error: {data_root} does not exist or is not a directory",
            file=sys.stderr,
        )
        return 2

    out = []
    out.append(KOTLIN_HEADER)
    for path in sorted(data_root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(data_root).as_posix()
        size = path.stat().st_size
        sha = sha256_of(path)
        rel_under_engine = f"espeak-ng-data/{rel}"
        url_path = f"$BASE/espeak-ng-data/{rel}"
        out.append(
            f'        EngineFile(\n'
            f'            relativePath = "{rel_under_engine}",\n'
            f'            url = "{url_path}",\n'
            f'            sha256 = "{sha}",\n'
            f'            sizeBytes = {size}L,\n'
            f'        ),\n'
        )
    out.append(KOTLIN_FOOTER)

    text = "".join(out)
    if args.output is None:
        sys.stdout.write(text)
    else:
        args.output.write_text(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
