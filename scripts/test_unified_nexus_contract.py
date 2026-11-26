#!/usr/bin/env python3
"""Contract: Nexus has exactly one owner-facing app and one integrated shell.

Checks the production manifest and Kotlin entrypoints; CI Android compilation
and on-device acceptance remain separate mandatory gates.
"""
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parents[1]
APP=ROOT/"app/src/main"
ANDROID="{http://schemas.android.com/apk/res/android}"
def attr(element,key):
    return element.get(ANDROID+key)
def check():
    manifest=ET.parse(APP/"AndroidManifest.xml").getroot()
    application=manifest.find("application")
    assert application is not None, "Missing application"
    launchers=[]
    for node in application.findall("activity")+application.findall("activity-alias"):
        for filt in node.findall("intent-filter"):
            actions={attr(a,"name") for a in filt.findall("action")}
            categories={attr(c,"name") for c in filt.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                launchers.append(attr(node,"name"))
    assert launchers==["com.asterion.nexus.NexusPremiumHomeActivity"], launchers
    assert len(application.findall("activity"))>1, "Native readers/workers must stay registered"
    shell=(APP/"java/com/asterion/nexus/NexusPremiumHomeActivity.kt").read_text()
    workspace=(APP/"java/com/asterion/nexus/NexusUnifiedWorkspace.kt").read_text()
    expected=['"Library"','"Search"','"Cloud"','"Organize"','"Intelligence"']
    assert all(x in shell for x in expected), "Missing integrated navigation"
    assert 'NexusWorkspace(section=section' in shell, "Section routing must stay in the same Activity"
    assert "startActivity(Intent(this,NexusSearchActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCloudActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCoreActivity::class.java))" not in shell
    for route in ["NexusUnifiedSearch(", "NexusUnifiedCloud(", "NexusUnifiedOrganize(", "NexusUnifiedIntelligence("]:
        assert route in workspace, "Missing unified view "+route
    gradle=(ROOT/"app/build.gradle.kts").read_text()
    assert 'applicationId = "com.asterion.nexus"' in gradle
    assert 'implementation(project(":fusion"))' in gradle
    assert 'implementation(project(":rounder"))' in gradle
    assert 'onQueryChanged(value)' in shell
    assert "FgTeraboxDocumentsProvider.PAGE_OFFSET" in workspace
    print("PASS: one Nexus launcher; shared Compose routing; internal Core/Rounder modules; debounced library and paged cloud")
if __name__=="__main__":
    check()
