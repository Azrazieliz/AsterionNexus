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
    expected=['"Library"','"Search"','"Transfers"','"Cloud"','"Organize"','"Intelligence"']
    assert all(x in shell for x in expected), "Missing integrated navigation"
    assert 'NexusWorkspace(section=section' in shell, "Section routing must stay in the same Activity"
    assert "startActivity(Intent(this,NexusSearchActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCloudActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCoreActivity::class.java))" not in shell
    for route in ["NexusUnifiedSearch(", "NexusUnifiedTransfers(", "NexusUnifiedCloud(", "NexusUnifiedOrganize(", "NexusUnifiedIntelligence("]:
        assert route in workspace, "Missing unified view "+route
    gradle=(ROOT/"app/build.gradle.kts").read_text()
    assert 'applicationId = "com.asterion.nexus"' in gradle
    assert 'implementation(project(":fusion"))' in gradle
    assert 'implementation(project(":rounder"))' in gradle
    assert 'onQueryChanged(value)' in shell
    assert "FgTeraboxDocumentsProvider.PAGE_OFFSET" in workspace
    visual=(APP/"java/com/asterion/nexus/NexusVisual.kt").read_text()
    icon=(APP/"res/drawable/ic_asterion_nexus.xml").read_text()
    assert "0xFFD4D9E1" in visual, "Owner silver color is not installed"
    assert "0xFF0E1013" in visual, "Charcoal canvas is not installed"
    assert '#D4D9E1' in icon and '#D4B47D' not in icon, "Launcher icon conflicts with approved silver palette"
    assert 'aspectRatio(ratio)' in shell and 'contentScale=ContentScale.Fit' in shell, "Library must use native-ratio preview images"
    assert 'maxWidth>=680.dp->3' in shell and 'else->2' in shell, "Image-first mobile gallery width not preserved"
    assert "GoldColor" not in shell and "C_GOLD" not in workspace
    assert "NexusVisual.silver" in workspace and "NexusVisual.silver" in shell
    assert 'NexusUnifiedTransfers(' in workspace, "Native transfer queue must remain in the single workspace"
    assert 'span={item->' in shell and 'GridItemSpan(maxLineSpan)' in shell, "Featured original gallery tile missing"
    assert 'listOf("Library","Search","Transfers","More")' in shell, "Compact four-item navigation missing"
    assert 'ModalBottomSheet(' in shell and 'onRoute(name)' in shell, "Source workflows must remain reachable"
    assert 'val ratio=thumbnail?.let{' in shell, "Media tiles must preserve original aspect ratio"
    registry=(APP/"java/com/asterion/nexus/NexusRegistry.java").read_text()
    assert 'searchPage(String query,int offset,int limit,String category)' in registry
    assert 'val chosenCategory=category' in shell and 'loadPage(reset=true)' in shell
    assert '"Audio"->m.startsWith("audio/")' in shell
    assert 'listOf("All","Images","Videos","Books","Audio","Other")' in shell
    viewer=(APP/"java/com/asterion/nexus/NexusImageViewerActivity.kt").read_text()
    router=(APP/"java/com/asterion/nexus/NexusFileOpen.java").read_text()
    assert 'NexusImageViewerActivity.class' in router, "Pictures must open inside Nexus"
    assert 'NexusImageViewerActivity' in [attr(x,"name").split(".")[-1] for x in application.findall("activity")], "Integrated viewer not registered"
    assert 'boundedImage(source)' in viewer and 'rememberTransformableState' in viewer, "Safe integrated image viewer missing"
    assert 'case "Books":' in registry and 'case "Other":' in registry
    print("PASS: one Nexus launcher; one native Compose workspace; actual transfers; charcoal/silver style; image-first original-aspect library")
if __name__=="__main__":
    check()
