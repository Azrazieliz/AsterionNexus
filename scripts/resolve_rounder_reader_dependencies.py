#!/usr/bin/env python3
"""Replay pinned upstream Rounder to restore missing document engine dependencies."""
from pathlib import Path
import tempfile
from import_native_sources import ROOT,clone,assemble,cp

CLASSES=("XmlUtil.java","ZipPackage.java")

def main():
    dst=ROOT/"rounder/src/main/java/com/openlocal/office/core"
    if all((dst/name).is_file() for name in CLASSES):
        raise RuntimeError("Dependency sources already present; no overwrite")
    with tempfile.TemporaryDirectory(prefix="nexus-reader-deps-") as directory:
        source=assemble(clone("rounder",Path(directory)))
        root=source/"app/src/main/java/com/openlocal/office/core"
        for name in CLASSES:
            if not (root/name).is_file():raise RuntimeError("Pinned Rounder missing "+name)
            if not (dst/name).exists():cp(root/name,dst/name)
        upstream=(source/"app/build.gradle.kts").read_text()
        original=(ROOT/"rounder/build.gradle.kts").read_text()
        # Copy only the exact, upstream-tested Apache POI coordinate versions.
        poi=[]
        for line in upstream.splitlines():
            if "poi:" in line or "poi-scratchpad:" in line or "poi-ooxml:" in line:
                if "implementation(" in line:poi.append(line.strip())
        if not any("poi:" in x for x in poi) or not any("poi-scratchpad:" in x for x in poi):
            raise RuntimeError("Rounder Apache POI dependency cannot be pinned")
        prefix='dependencies {\n'
        if prefix not in original:raise RuntimeError("Nexus Rounder Gradle anchor not found")
        for line in poi:
            if line not in original:original=original.replace(prefix,prefix+" "+line+"\n",1)
        (ROOT/"rounder/build.gradle.kts").write_text(original)
        print("Recovered native reader helpers:",", ".join(CLASSES))
        print("Copied donor dependency identities:",poi)

if __name__=="__main__":main()
