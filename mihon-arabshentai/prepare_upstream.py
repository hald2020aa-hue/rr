#!/usr/bin/env python3
"""Apply a source-only overlay to the pinned Keiyoushi extension build.

This does not modify the Mihon app or the seven existing Aniyomi extensions.
"""
from __future__ import annotations

import re
import shutil
from pathlib import Path

BUNDLE = Path(__file__).resolve().parent
ROOT = BUNDLE.parent / "keiyoushi"
UPSTREAM_SRC = ROOT / "src/ar/arabshentai"


def prepare(root: Path = ROOT, bundle: Path = BUNDLE) -> None:
    source = root / "src/ar/arabshentai"
    settings = root / "settings.gradle.kts"
    if not source.is_dir() or not settings.is_file():
        raise RuntimeError("Checkout keiyoushi/extensions-source at the pinned revision first")

    text = settings.read_text(encoding="utf-8")
    edited, count = re.subn(
        r"(?m)^loadAllIndividualExtensions\(\)\s*$",
        'loadIndividualExtension("ar", "arabshentai")',
        text,
        count=1,
    )
    if count != 1:
        raise RuntimeError("Keiyoushi settings layout changed; refusing a broken build")
    settings.write_text(edited, encoding="utf-8")

    files = (
        "build.gradle.kts",
        "src/eu/kanade/tachiyomi/extension/ar/arabshentai/ArabsHentai.kt",
        "src/eu/kanade/tachiyomi/extension/ar/arabshentai/Filters.kt",
    )
    for rel in files:
        source_file = bundle / rel
        if not source_file.is_file():
            raise FileNotFoundError(source_file)
        destination = source / rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_file, destination)
        print("Overlay:", destination.relative_to(root))


if __name__ == "__main__":
    prepare()
