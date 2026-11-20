#!/usr/bin/env python3
"""Repair the Compose delegated-State import in the generated duration badge."""
from pathlib import Path

path = Path(
    "app/src/main/java/eu/kanade/presentation/browse/anime/components/"
    "AnimeVideoDurationBadge.kt"
)
if not path.is_file():
    raise SystemExit(f"Duration badge was not generated: {path}")

source = path.read_text(encoding="utf-8")
required = "import androidx.compose.runtime.getValue\n"
anchor = "import androidx.compose.runtime.produceState\n"
if required not in source:
    if source.count(anchor) != 1:
        raise SystemExit("Unexpected duration badge imports; refusing a blind edit")
    source = source.replace(anchor, required + anchor, 1)
    path.write_text(source, encoding="utf-8")
print("PASS: Compose getValue import is present in AnimeVideoDurationBadge.kt")
