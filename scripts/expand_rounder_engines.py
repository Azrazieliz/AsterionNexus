#!/usr/bin/env python3
"""Materialize missing native Rounder reader/player/provider engines from accepted assembly.

Only copies pinned assembled source. Does not touch pre-existing application classes,
account data, provider secrets, installed apps or donor Git refs.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import tempfile
from import_native_sources import ROOT, clone, assemble, cp, DONORS

CLASSES = (
    "FgTeraboxDocumentsProvider.java",
    "FgTeraboxMediaDataSource.java",
    "FgGrowingMediaDataSource.java",
    "FgMediaPlayer.java",
    "ReaderCovers.java",
    "UniversalReader.java",
    "LegacyOfficeReader.java",
    "RichDocxPreview.java",
    "FgRounderVideoPreviews.java",
    "FgRounderOriginalPreviews.java",
    "KoikatsuCards.java",
)
ASSETS = (
    "universal-reader.js",
    "epub-reader.js",
    "unified-viewer-controls.js",
    "reader-controls.js",
    "premium-library.css",
    "premium-library.js",
    "rounder-premium-theme.css",
)
SRC_AUTHORITY = "com.openlocal.office.terabox.documents"
NEXUS_AUTHORITY = "com.asterion.nexus.terabox.documents"

def main():
    for path in CLASSES:
        if (ROOT / "rounder/src/main/java/com/openlocal/office/core" / path).exists():
            raise RuntimeError("Rounder expansion already performed: " + path)
    with tempfile.TemporaryDirectory(prefix="nexus-rounder-extension-") as directory:
        rounder = clone("rounder", Path(directory))
        built = assemble(rounder)
        source = built / "app/src/main/java/com/openlocal/office/core"
        target = ROOT / "rounder/src/main/java/com/openlocal/office/core"
        hashes = []
        for cls in CLASSES:
            if not (source / cls).is_file():
                raise RuntimeError("Pinned Rounder build omitted engine: " + cls)
            copied = target / cls
            cp(source / cls, copied)
            contents = copied.read_text(encoding="utf-8")
            if SRC_AUTHORITY in contents:
                contents = contents.replace(SRC_AUTHORITY, NEXUS_AUTHORITY)
                copied.write_text(contents, encoding="utf-8")
            hashes.append({"path": copied.relative_to(ROOT).as_posix(),
                           "sha256": hashlib.sha256(copied.read_bytes()).hexdigest()})
        assets = ROOT / "rounder/src/main/assets"
        for name in ASSETS:
            origin = built / "app/src/main/assets" / name
            if not origin.is_file():
                origin = rounder / "openlocaloffice-build/additions" / name
            if not origin.is_file():
                raise RuntimeError("Missing accepted Rounder reader asset: " + name)
            copied = assets / name
            cp(origin, copied)
            hashes.append({"path": copied.relative_to(ROOT).as_posix(),
                           "sha256": hashlib.sha256(copied.read_bytes()).hexdigest()})
        metadata = ROOT / "spec/rounder-engine-extension.v1.json"
        if metadata.exists():
            raise RuntimeError("Refusing to overwrite source provenance")
        metadata.write_text(json.dumps({
            "schema": "asterion.nexus.rounder-extension.v1",
            "donor": dict(repository=DONORS["rounder"][0], branch=DONORS["rounder"][1],
                          commit=DONORS["rounder"][2]),
            "assembly": "entire ordered upstream build workflow through source-export gate",
            "collision_resolution": {"documents_provider": NEXUS_AUTHORITY},
            "files": hashes,
            "device_verified": False
        }, indent=2) + "\n", encoding="utf-8")
        print("Rounder reader/player/provider source integrated:", len(hashes), "files")

if __name__ == "__main__":
    main()
