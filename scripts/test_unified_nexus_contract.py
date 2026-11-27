#!/usr/bin/env python3
"""Contract: Nexus has exactly one owner-facing app and one integrated shell.

Checks the production manifest and Kotlin entrypoints; CI Android compilation
and on-device acceptance remain separate mandatory gates.
"""
import pathlib
import re
import sqlite3
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
    shell=(APP/"java/com/asterion/nexus/NexusPremiumHomeActivity.kt").read_text(encoding="utf-8")
    workspace=(APP/"java/com/asterion/nexus/NexusUnifiedWorkspace.kt").read_text(encoding="utf-8")
    assert 'val destinations=listOf("Library","Organising","Downloading")' in shell, "Exactly three permanent destinations required"
    assert 'activeSection by mutableStateOf("Cloud")' in shell, "Library must start with TeraBox"
    assert 'detectHorizontalDragGestures' in shell, "Missing contextual swipe navigation"
    assert all(x in shell for x in ['"Gallery"','"Books"','"Videos"','"Assets/Files"','"Activity"','"Taxonomy"','"Review"','"Search"','"Transfers"'])
    assert 'NexusWorkspace(section=section' in shell, "Section routing must stay in the same Activity"
    assert "startActivity(Intent(this,NexusSearchActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCloudActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCoreActivity::class.java))" not in shell
    for route in ["NexusUnifiedSearch(", "NexusUnifiedTransfers(", "NexusUnifiedCloud(", "NexusUnifiedOrganize(", "NexusUnifiedIntelligence("]:
        assert route in workspace, "Missing unified view "+route
    gradle=(ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'applicationId = "com.asterion.nexus"' in gradle
    assert 'implementation(project(":fusion"))' in gradle
    assert 'implementation(project(":rounder"))' in gradle
    assert 'onQueryChanged(value)' in shell
    assert "FgTeraboxDocumentsProvider.PAGE_OFFSET" in workspace
    visual=(APP/"java/com/asterion/nexus/NexusVisual.kt").read_text(encoding="utf-8")
    icon=(APP/"res/drawable/ic_asterion_nexus.xml").read_text(encoding="utf-8")
    assert "0xFFD4D9E1" in visual, "Owner silver color is not installed"
    assert "0xFF0E1013" in visual, "Charcoal canvas is not installed"
    assert '#D4D9E1' in icon and '#D4B47D' not in icon, "Launcher icon conflicts with approved silver palette"
    assert 'aspectRatio(ratio)' in shell and 'contentScale=ContentScale.Fit' in shell, "Library must use native-ratio preview images"
    assert 'maxWidth>=680.dp->3' in shell and 'else->2' in shell, "Image-first mobile gallery width not preserved"
    assert "GoldColor" not in shell and "C_GOLD" not in workspace
    assert "NexusVisual.silver" in workspace and "NexusVisual.silver" in shell
    assert 'NexusUnifiedTransfers(' in workspace, "Native transfer queue must remain in the single workspace"
    assert 'span={item->' in shell and 'GridItemSpan(maxLineSpan)' in shell, "Featured original gallery tile missing"
    assert 'destinations.forEach' in shell, "Permanent navigation must be generated from one registry"
    assert 'select(name,when(name)' in shell and 'onRoute("Cloud")' in shell, "Source workflows must remain reachable"
    assert 'val ratio=thumbnail?.let{' in shell, "Media tiles must preserve original aspect ratio"
    registry=(APP/"java/com/asterion/nexus/NexusRegistry.java").read_text(encoding="utf-8")
    assert 'searchPage(String query,int offset,int limit,String category)' in registry
    assert 'val chosenCategory=category' in shell and 'loadPage(reset=true)' in shell
    assert '"Audio"->m.startsWith("audio/")' in shell
    assert 'case "Assets":' in registry and '"Assets/Files"->"Assets"' in shell
    assert 'HorizontalPager(state=pager' in shell and 'PdfRenderer(fd)' in shell and 'ZipFile(file)' in shell
    assert '"Review"->NexusUnifiedOrganize("Reviews")' in workspace
    assert '"Taxonomy"->NexusUnifiedOrganize("Fusion")' in workspace
    viewer=(APP/"java/com/asterion/nexus/NexusImageViewerActivity.kt").read_text(encoding="utf-8")
    router=(APP/"java/com/asterion/nexus/NexusFileOpen.java").read_text(encoding="utf-8")
    assert 'NexusImageViewerActivity.class' in router, "Pictures must open inside Nexus"
    assert 'NexusImageViewerActivity' in [attr(x,"name").split(".")[-1] for x in application.findall("activity")], "Integrated viewer not registered"
    assert 'boundedImage(source)' in viewer and 'rememberTransformableState' in viewer, "Safe integrated image viewer missing"
    assert 'case "Books":' in registry and 'case "Other":' in registry
    taxonomy_revision_regression()
    print("PASS: one Nexus launcher; approved navigation; native transfer routes; real cover flow; taxonomy revision SQL fixture")

def taxonomy_revision_regression():
    # Execute exact SQL from the Kotlin schema using SQLite. This is a
    # source/SQL regression, not an Android SQLiteOpenHelper migration test.
    schema=(ROOT/"fusion/src/main/java/com/ailm/android/runtime/FusionDatabaseSchema.kt").read_text(encoding="utf-8")
    ensure=schema.split("fun ensureArtifacts(",1)[1].split("private fun createVisualLearningTables",1)[0]
    assert "createTaxonomyRevision(db)" in ensure, "Legacy database repair must create taxonomy revision artifacts"
    body=schema.split("private fun createTaxonomyRevision(",1)[1].split("private fun createCharacterSheetTable(",1)[0]
    statements=re.findall(r'db\.execSQL\("([^"\n]+)"\)',body)
    trigger=re.search(r'db\.execSQL\("""(CREATE TRIGGER.*?)"""\)',body,re.S)
    assert len(statements)==2 and trigger
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE fusion_tags(id TEXT PRIMARY KEY)")
        db.execute("CREATE TABLE fusion_tag_aliases(id TEXT PRIMARY KEY)")
        db.execute("CREATE TABLE images(image_id INTEGER PRIMARY KEY, rating INTEGER)")
        db.execute("INSERT INTO images VALUES(1,87)")
        db.execute("INSERT INTO fusion_tags VALUES('existing')")
        def repair():
            for sql in statements:
                db.execute(sql)
            for table in ("fusion_tags","fusion_tag_aliases"):
                for operation in ("INSERT","UPDATE","DELETE"):
                    sql=trigger.group(1).replace("${table}",table).replace("$table",table).replace("$operation",operation)
                    db.execute(sql)
        repair()
        assert db.execute("SELECT revision FROM fusion_taxonomy_revision WHERE id=1").fetchone()==(0,)
        db.execute("INSERT INTO fusion_tags VALUES('new')")
        db.execute("UPDATE fusion_tags SET id='updated' WHERE id='new'")
        db.execute("DELETE FROM fusion_tags WHERE id='updated'")
        db.execute("INSERT INTO fusion_tag_aliases VALUES('alias')")
        repair()
        assert db.execute("SELECT revision FROM fusion_taxonomy_revision WHERE id=1").fetchone()==(4,)
        assert db.execute("SELECT rating FROM images WHERE image_id=1").fetchone()==(87,)
        assert db.execute("SELECT id FROM fusion_tags").fetchall()==[("existing",)]
        assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'").fetchone()==(6,)

if __name__=="__main__":
    check()
